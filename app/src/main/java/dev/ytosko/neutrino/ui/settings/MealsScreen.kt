package dev.ytosko.neutrino.ui.settings

import kotlinx.coroutines.flow.first
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import java.time.LocalDate
import dev.ytosko.neutrino.data.reminders.DoseReminders
import dev.ytosko.neutrino.domain.RamadanCities
import dev.ytosko.neutrino.domain.RamadanCity
import dev.ytosko.neutrino.appContainer
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
    val ramadanSync = context.appContainer.ramadanSync
    var choosingCity by remember { mutableStateOf(false) }
    var ramadanLoading by remember { mutableStateOf(false) }
    var ramadanFailed by remember { mutableStateOf(false) }
    /** Fetches (or refreshes) the Ramadan schedule, then re-books reminders. */
    fun loadRamadan(force: Boolean) {
        scope.launch {
            ramadanLoading = true
            ramadanFailed = !runCatching { ramadanSync.ensure(force = force) }.getOrDefault(false)
            ramadanLoading = false
            val now = settings.settings.first()
            if (now.remindersEnabled) MealReminders.scheduleAll(context)
            DoseReminders.sync(context)
        }
    }

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
                    if (s.ramadanToday) {
                        // In Ramadan the Sehri and Iftar reminders take over (set below).
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            stringResource(R.string.ramadan_reminders_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.md),
                        )
                    } else {
                        listOf(MealType.Breakfast, MealType.Lunch, MealType.Dinner).forEach { type ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            TimeRow(type, stringResource(R.string.meals_remind_at), s.reminder(type)) {
                                editing = TimeTarget.Reminder(type)
                            }
                        }
                    }
                }
            }

            Group(stringResource(R.string.ramadan_section)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .toggleable(value = s.ramadan, role = Role.Switch) { on ->
                            scope.launch {
                                settings.setRamadan(on)
                                // A city is needed for the times: ask for it the first time.
                                if (on && s.ramadanCity == null) choosingCity = true else if (on) loadRamadan(force = false)
                                if (!on) {
                                    if (s.remindersEnabled) MealReminders.scheduleAll(context)
                                    DoseReminders.sync(context)
                                }
                            }
                        }
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    IconBadge(icon = R.drawable.ic_moon, container = NeutrinoTheme.colors.indigo.container, content = NeutrinoTheme.colors.indigo.content, size = 40.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ramadan_mode), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.ramadan_mode_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.ramadan, onCheckedChange = null)
                }
                if (s.ramadan) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.Button) { choosingCity = true }
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.ramadan_city), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            s.ramadanCity?.name ?: stringResource(R.string.ramadan_city_choose),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (s.ramadanCity == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RamadanStatus(s, ramadanLoading, ramadanFailed, onRetry = { loadRamadan(force = true) })
                }
            }
        }
    }

    if (choosingCity) {
        CityDialog(
            selected = current?.ramadanCity?.id,
            onPick = { city ->
                choosingCity = false
                scope.launch {
                    settings.setRamadanCity(city)
                    loadRamadan(force = true)
                }
            },
            onDismiss = { choosingCity = false },
        )
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
    // Ramadan's meals have their own times (Sehri ends, Iftar), not window starts.
    MealType.Sehri -> ramadan?.sehriEnds ?: breakfast
    MealType.Iftar -> ramadan?.iftar ?: dinner
}

/** Throws if the new time would put the meals out of order. */
private fun MealWindows.with(type: MealType, time: LocalTime): MealWindows = when (type) {
    MealType.Breakfast -> copy(breakfast = time)
    MealType.Lunch -> copy(lunch = time)
    MealType.Snack -> copy(snack = time)
    MealType.Dinner -> copy(dinner = time)
    MealType.Sehri, MealType.Iftar -> this
}

private fun AppSettings.reminder(type: MealType): LocalTime = when (type) {
    MealType.Breakfast -> breakfastReminder
    MealType.Lunch -> lunchReminder
    else -> dinnerReminder
}

/** Where the Ramadan schedule stands: loading, the running or next Ramadan with today's times, or a failure. */
@Composable
private fun RamadanStatus(s: AppSettings, loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    val today = LocalDate.now()
    val days = s.ramadanDays.takeIf { s.ramadanCityOfDays == s.ramadanCity?.id }.orEmpty()
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val times = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val todayDay = days.firstOrNull { it.date == today }
    val text = when {
        s.ramadanCity == null -> stringResource(R.string.ramadan_status_no_city)
        loading -> stringResource(R.string.ramadan_status_loading)
        todayDay != null -> stringResource(
            R.string.ramadan_status_today,
            days.indexOf(todayDay) + 1,
            todayDay.sehriEnds.format(times),
            todayDay.iftar.format(times),
        )
        days.isNotEmpty() && days.first().date > today -> stringResource(R.string.ramadan_status_next, days.first().date.format(dates))
        failed -> stringResource(R.string.ramadan_status_failed)
        else -> stringResource(R.string.ramadan_status_loading)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (failed && days.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.ramadan_source_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (failed && days.isEmpty()) TextButton(onClick = onRetry) { Text(stringResource(R.string.ramadan_retry)) }
    }
}

/** The cities Ramadan times can be worked out for; Bangladesh's first. */
@Composable
private fun CityDialog(selected: String?, onPick: (RamadanCity) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ramadan_city_title)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(RamadanCities.all.size) { index ->
                    val city = RamadanCities.all[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(city) }
                            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(city.name, style = MaterialTheme.typography.bodyLarge)
                            Text(city.country, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (city.id == selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}
