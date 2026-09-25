package dev.ytosko.neutrino.ui.settings

import dev.ytosko.neutrino.ui.components.NeutrinoSnackbarHost
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.reminders.MealReminders
import dev.ytosko.neutrino.data.settings.AppSettings
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.MealWindows
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.components.mealTypeColors
import dev.ytosko.neutrino.ui.components.mealTypeIcon
import dev.ytosko.neutrino.ui.components.mealTypeLabel
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What a time row edits. */
private sealed interface TimeTarget {
    data class MealStart(val type: MealType) : TimeTarget
    data class Reminder(val type: MealType) : TimeTarget
}

/**
 * Settings → Meals & reminders: when each meal starts (used to pick the meal type
 * automatically) and the daily reminders.
 */
@Composable
fun MealsScreen(settings: SettingsRepository, onBack: () -> Unit) {
    val current by settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<TimeTarget?>(null) }
    var canNotify by remember { mutableStateOf(MealReminders.canNotify(context)) }
    LifecycleResumeEffect(Unit) {
        canNotify = MealReminders.canNotify(context)
        onPauseOrDispose { }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        canNotify = MealReminders.canNotify(context)
    }
    val orderError = stringResource(R.string.meals_order_error)

    SetupScaffold(
        title = stringResource(R.string.meals_title),
        subtitle = stringResource(R.string.meals_body),
        onBack = onBack,
        bottomBar = { NeutrinoSnackbarHost(snackbar) },
    ) {
        val s = current ?: return@SetupScaffold
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Group(stringResource(R.string.meals_starts)) {
                listOf(MealType.Breakfast, MealType.Lunch, MealType.Snack, MealType.Dinner).forEachIndexed { index, type ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TimeRow(type, stringResource(R.string.meals_starts_at), s.mealWindows.start(type)) {
                        editing = TimeTarget.MealStart(type)
                    }
                }
            }

            Group(stringResource(R.string.settings_section_reminders)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .toggleable(value = s.remindersEnabled, role = Role.Switch) { on ->
                            scope.launch {
                                settings.setRemindersEnabled(on)
                                if (on) MealReminders.scheduleAll(context) else MealReminders.cancelAll(context)
                            }
                            if (on && !canNotify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    IconBadge(icon = R.drawable.ic_bell, container = NeutrinoTheme.colors.amber.container, content = NeutrinoTheme.colors.amber.content, size = 40.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_reminders), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.meals_reminders_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.remindersEnabled, onCheckedChange = null)
                }
                if (s.remindersEnabled) {
                    if (!canNotify) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            stringResource(R.string.settings_reminders_blocked),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                    )
                                }
                                .padding(Spacing.md),
                        )
                    }
                    listOf(MealType.Breakfast, MealType.Lunch, MealType.Dinner).forEach { type ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TimeRow(type, stringResource(R.string.meals_remind_at), s.reminder(type)) {
                            editing = TimeTarget.Reminder(type)
                        }
                    }
                }
            }
        }
    }

    val target = editing
    val s = current
    if (target != null && s != null) {
        val initial = when (target) {
            is TimeTarget.MealStart -> s.mealWindows.start(target.type)
            is TimeTarget.Reminder -> s.reminder(target.type)
        }
        TimeDialog(
            initial = initial,
            onDismiss = { editing = null },
            onConfirm = { time ->
                editing = null
                scope.launch {
                    when (target) {
                        is TimeTarget.MealStart -> {
                            val windows = runCatching { s.mealWindows.with(target.type, time) }.getOrNull()
                            if (windows == null || !settings.setMealWindows(windows)) snackbar.showSnackbar(orderError)
                        }
                        is TimeTarget.Reminder -> {
                            settings.setReminderTimes(
                                breakfast = if (target.type == MealType.Breakfast) time else s.breakfastReminder,
                                lunch = if (target.type == MealType.Lunch) time else s.lunchReminder,
                                dinner = if (target.type == MealType.Dinner) time else s.dinnerReminder,
                            )
                            if (s.remindersEnabled) MealReminders.scheduleAll(context)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.xs),
        )
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) { Column { content() } }
    }
}

@Composable
private fun TimeRow(type: MealType, caption: String, time: LocalTime, onClick: () -> Unit) {
    val (container, content) = mealTypeColors(type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        IconBadge(mealTypeIcon(type), container = container, content = content, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(mealTypeLabel(type), style = MaterialTheme.typography.titleSmall)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.review_time_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

private fun MealWindows.start(type: MealType): LocalTime = when (type) {
    MealType.Breakfast -> breakfast
    MealType.Lunch -> lunch
    MealType.Snack -> snack
    MealType.Dinner -> dinner
}

/** Throws if the new time would put the meals out of order. */
private fun MealWindows.with(type: MealType, time: LocalTime): MealWindows = when (type) {
    MealType.Breakfast -> copy(breakfast = time)
    MealType.Lunch -> copy(lunch = time)
    MealType.Snack -> copy(snack = time)
    MealType.Dinner -> copy(dinner = time)
}

private fun AppSettings.reminder(type: MealType): LocalTime = when (type) {
    MealType.Breakfast -> breakfastReminder
    MealType.Lunch -> lunchReminder
    else -> dinnerReminder
}
