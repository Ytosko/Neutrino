package dev.ytosko.neutrino.ui.settings

import dev.ytosko.neutrino.data.reminders.DoseReminders
import dev.ytosko.neutrino.data.medicine.MedicineKind
import dev.ytosko.neutrino.data.medicine.MedicineEntity
import dev.ytosko.neutrino.data.health.HealthKind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import dev.ytosko.neutrino.ui.components.AlertStyle
import dev.ytosko.neutrino.ui.components.AlertButton
import dev.ytosko.neutrino.ui.components.IosAlert
import dev.ytosko.neutrino.data.glucose.PairedMeter
import dev.ytosko.neutrino.data.reminders.MealReminders
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.provider.Settings
import android.os.Build
import android.content.Intent
import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.BuildConfig
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.backup.BackupState
import dev.ytosko.neutrino.data.settings.AppSettings
import dev.ytosko.neutrino.ui.backup.formatWhen
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.health.HealthConnectViewModel
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow
import dev.ytosko.neutrino.ui.theme.Tint
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.data.settings.AppLanguage
import dev.ytosko.neutrino.widget.NeutrinoWidget
import kotlinx.coroutines.launch
import dev.ytosko.neutrino.ui.lock.AppLock
import dev.ytosko.neutrino.domain.GlucoseUnit
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.data.reminders.WeeklySummary
import dev.ytosko.neutrino.data.reminders.TestReminders
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog

@Composable
fun SettingsScreen(
    settings: Flow<AppSettings>,
    backup: Flow<BackupState>,
    onOpenBackup: () -> Unit,
    onOpenMeals: () -> Unit,
    meters: Flow<List<PairedMeter>>,
    onOpenMeter: () -> Unit,
    healthViewModel: HealthConnectViewModel,
    onBack: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onOpenGoals: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenMedicines: () -> Unit,
    medicines: Flow<List<MedicineEntity>>,
    repository: SettingsRepository,
) {
    val appSettings by settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val c = NeutrinoTheme.colors
    val backupState by backup.collectAsStateWithLifecycle(initialValue = null)
    val pairedMeters by meters.collectAsStateWithLifecycle(initialValue = emptyList())
    val medicineList by medicines.collectAsStateWithLifecycle(initialValue = emptyList())
    val health by healthViewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LifecycleResumeEffect(Unit) {
        healthViewModel.refresh()
        onPauseOrDispose { }
    }
    val privacyUrl = stringResource(R.string.url_privacy)
    val termsUrl = stringResource(R.string.url_terms)
    val sourceUrl = stringResource(R.string.url_source)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosingUnit by remember { mutableStateOf(false) }
    var choosingLanguage by remember { mutableStateOf(false) }
    val language = remember(choosingLanguage) { AppLanguage.current(context) }
    var lockUnavailable by remember { mutableStateOf(false) }
    // Reminders and the weekly summary need notifications (Android 13+ asks once).
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Glucose import: Health Connect asks the user to allow reading blood glucose.
    val glucoseReadLauncher = rememberLauncherForActivityResult(healthViewModel.permissionContract()) { granted ->
        healthViewModel.onGlucoseReadResult(granted) { repository.setGlucoseImport(true) }
    }
    fun askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !MealReminders.canNotify(context)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    SetupScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        Section(stringResource(R.string.settings_section_ai)) {
            val provider = appSettings.activeProvider
            SettingRow(
                icon = R.drawable.ic_sparkles,
                tint = c.violet,
                title = stringResource(R.string.settings_ai_provider),
                value = if (provider != null && appSettings.aiReady) {
                    "${provider.displayName} · ${appSettings.models[provider]}"
                } else {
                    stringResource(R.string.settings_ai_not_set)
                },
                onClick = onOpenAi,
            )
        }
        Section(stringResource(R.string.settings_section_health)) {
            SettingRow(
                icon = R.drawable.ic_heart_pulse,
                tint = c.coral,
                title = stringResource(R.string.settings_section_health),
                value = when {
                    health.granted -> stringResource(R.string.settings_hc_status_connected)
                    health.allowed.isEmpty() -> stringResource(R.string.settings_hc_status_disconnected)
                    else -> stringResource(
                        R.string.settings_hc_status_missing,
                        health.missing.map {
                            stringResource(
                                when (it) {
                                    HealthKind.Nutrition -> R.string.hc_kind_nutrition
                                    HealthKind.Hydration -> R.string.hc_kind_water
                                    HealthKind.Glucose -> R.string.hc_kind_glucose
                                },
                            )
                        }.joinToString(", "),
                    )
                },
                valueColor = if (health.allowed.isNotEmpty() && !health.granted) MaterialTheme.colorScheme.error else null,
                onClick = onOpenHealthConnect,
            )
        }
        Section(stringResource(R.string.settings_section_backup)) {
            val current = backupState
            SettingRow(
                icon = R.drawable.ic_archive,
                tint = c.sky,
                title = stringResource(R.string.backup_settings_title),
                value = when {
                    current == null -> null
                    !current.configured -> stringResource(R.string.settings_backup_off)
                    current.needsAttention -> stringResource(R.string.settings_backup_attention)
                    else -> current.lastLocalAt?.let { stringResource(R.string.settings_backup_last, formatWhen(it)) }
                        ?: stringResource(R.string.backup_never)
                },
                onClick = onOpenBackup,
            )
        }
        Section(stringResource(R.string.settings_section_meter)) {
            SettingRow(
                icon = R.drawable.ic_activity,
                tint = c.rose,
                title = stringResource(R.string.meters_title),
                value = when (pairedMeters.size) {
                    0 -> stringResource(R.string.settings_meter_off)
                    1 -> pairedMeters.first().meterModel.displayName
                    else -> pluralStringResource(R.plurals.settings_meters_count, pairedMeters.size, pairedMeters.size)
                },
                onClick = onOpenMeter,
            )
            RowDivider()
            SettingRow(
                icon = R.drawable.ic_chart_column,
                tint = c.rose,
                title = stringResource(R.string.settings_glucose_unit),
                value = appSettings.glucoseUnit.label,
                onClick = { choosingUnit = true },
            )
            RowDivider()
            SwitchRow(
                icon = R.drawable.ic_heart_pulse,
                tint = c.rose,
                title = stringResource(R.string.settings_glucose_import),
                subtitle = stringResource(
                    if (appSettings.glucoseImport && health.availability != null && !health.readsGlucose) {
                        R.string.settings_glucose_import_needs_permission
                    } else {
                        R.string.settings_glucose_import_body
                    },
                ),
                checked = appSettings.glucoseImport && (health.availability == null || health.readsGlucose),
                onChange = { on ->
                    if (on) {
                        if (health.availability == dev.ytosko.neutrino.data.health.HealthConnectAvailability.Available) {
                            glucoseReadLauncher.launch(setOf(healthViewModel.glucoseReadPermission))
                        } else {
                            onOpenHealthConnect()
                        }
                    } else {
                        scope.launch { repository.setGlucoseImport(false) }
                    }
                },
            )
            RowDivider()
            SwitchRow(
                icon = R.drawable.ic_bell,
                tint = c.rose,
                title = stringResource(R.string.settings_test_reminder),
                subtitle = stringResource(R.string.settings_test_reminder_body),
                checked = appSettings.afterMealReminder,
                onChange = { on ->
                    if (on) askNotificationsIfNeeded()
                    scope.launch {
                        repository.setAfterMealReminder(on)
                        if (!on) TestReminders.cancel(context)
                    }
                },
            )
            RowDivider()
            SwitchRow(
                icon = R.drawable.ic_smartphone,
                tint = c.rose,
                title = stringResource(R.string.settings_widget_glucose),
                subtitle = stringResource(R.string.settings_widget_glucose_body),
                checked = appSettings.widgetShowsGlucose,
                onChange = { on -> scope.launch { repository.setWidgetShowsGlucose(on); NeutrinoWidget.refresh(context) } },
            )
        }
        Section(stringResource(R.string.settings_section_medicine)) {
            SwitchRow(
                icon = R.drawable.ic_pill,
                tint = c.violet,
                title = stringResource(R.string.settings_takes_medicine),
                subtitle = stringResource(R.string.settings_takes_medicine_body),
                checked = appSettings.takesMedicine,
                onChange = { on -> scope.launch { repository.setTakesMedicine(on); DoseReminders.sync(context) } },
            )
            RowDivider()
            SwitchRow(
                icon = R.drawable.ic_syringe,
                tint = c.cyan,
                title = stringResource(R.string.settings_uses_insulin),
                subtitle = stringResource(R.string.settings_uses_insulin_body),
                checked = appSettings.usesInsulin,
                onChange = { on -> scope.launch { repository.setUsesInsulin(on); DoseReminders.sync(context) } },
            )
            if (appSettings.medicinesOn) {
                RowDivider()
                val shown = medicineList.count {
                    (it.kindEnum == MedicineKind.Medicine && appSettings.takesMedicine) || (it.kindEnum == MedicineKind.Insulin && appSettings.usesInsulin)
                }
                SettingRow(
                    icon = R.drawable.ic_bell,
                    tint = c.violet,
                    title = stringResource(R.string.settings_my_medicines),
                    value = if (shown == 0) stringResource(R.string.settings_medicines_none) else pluralStringResource(R.plurals.settings_medicines_count, shown, shown),
                    onClick = {
                        askNotificationsIfNeeded()
                        onOpenMedicines()
                    },
                )
            }
        }
        Section(stringResource(R.string.settings_section_goals)) {
            SettingRow(
                icon = R.drawable.ic_chart_column,
                tint = c.green,
                title = stringResource(R.string.goals_title),
                value = goalsSummary(appSettings),
                onClick = onOpenGoals,
            )
        }
        Section(stringResource(R.string.settings_section_meals)) {
            val time = { t: java.time.LocalTime -> t.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)) }
            SettingRow(
                icon = R.drawable.ic_bell,
                tint = c.amber,
                title = stringResource(R.string.meals_title),
                value = if (appSettings.remindersEnabled) {
                    stringResource(
                        R.string.settings_meals_value,
                        time(appSettings.breakfastReminder),
                        time(appSettings.lunchReminder),
                        time(appSettings.dinnerReminder),
                    )
                } else {
                    stringResource(R.string.settings_meals_off)
                },
                onClick = onOpenMeals,
            )
            RowDivider()
            SwitchRow(
                icon = R.drawable.ic_history,
                tint = c.amber,
                title = stringResource(R.string.settings_weekly),
                subtitle = stringResource(R.string.settings_weekly_body),
                checked = appSettings.weeklySummary,
                onChange = { on ->
                    if (on) askNotificationsIfNeeded()
                    scope.launch {
                        repository.setWeeklySummary(on)
                        WeeklySummary.sync(context)
                    }
                },
            )
        }
        Section(stringResource(R.string.settings_section_data)) {
            SettingRow(
                icon = R.drawable.ic_archive,
                tint = c.indigo,
                title = stringResource(R.string.export_title),
                value = stringResource(R.string.settings_export_value),
                onClick = onOpenExport,
            )
        }
        Section(stringResource(R.string.settings_section_privacy)) {
            SwitchRow(
                icon = R.drawable.ic_lock,
                tint = c.slate,
                title = stringResource(R.string.settings_app_lock),
                subtitle = stringResource(R.string.settings_app_lock_body),
                checked = appSettings.appLock,
                onChange = { on ->
                    val activity = context as? FragmentActivity
                    when {
                        !on -> scope.launch { repository.setAppLock(false) }
                        activity == null || !AppLock.available(context) -> lockUnavailable = true
                        // Prove it works before turning it on, so nobody locks themselves out.
                        else -> AppLock.authenticate(activity, onSuccess = { scope.launch { repository.setAppLock(true) } })
                    }
                },
            )
            RowDivider()
            SwitchRow(
                icon = R.drawable.ic_eye_off,
                tint = c.slate,
                title = stringResource(R.string.settings_hide_recents),
                subtitle = stringResource(R.string.settings_hide_recents_body),
                checked = appSettings.hideInRecents,
                onChange = { on -> scope.launch { repository.setHideInRecents(on) } },
            )
        }
        Section(stringResource(R.string.settings_section_general)) {
            SettingRow(
                icon = R.drawable.ic_globe,
                tint = c.cyan,
                title = stringResource(R.string.settings_language),
                value = languageName(language),
                onClick = { choosingLanguage = true },
            )
        }
        Section(stringResource(R.string.settings_section_about)) {
            SettingRow(icon = R.drawable.ic_shield_check, tint = c.slate, title = stringResource(R.string.settings_privacy), value = null, external = true) { uriHandler.openUri(privacyUrl) }
            RowDivider()
            SettingRow(icon = R.drawable.ic_info, tint = c.slate, title = stringResource(R.string.settings_terms), value = null, external = true) { uriHandler.openUri(termsUrl) }
            RowDivider()
            SettingRow(icon = R.drawable.ic_code, tint = c.slate, title = stringResource(R.string.settings_source), value = null, external = true) { uriHandler.openUri(sourceUrl) }
        }
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.lg),
        )
    }

    if (choosingUnit) {
        IosAlert(
            title = stringResource(R.string.settings_glucose_unit),
            message = stringResource(R.string.settings_glucose_unit_body),
            buttons = GlucoseUnit.entries.map { unit ->
                AlertButton(if (appSettings.glucoseUnit == unit) "✓  ${unit.label}" else unit.label) {
                    scope.launch { repository.setGlucoseUnit(unit); NeutrinoWidget.refresh(context) }
                    choosingUnit = false
                }
            } + AlertButton(stringResource(R.string.backup_cancel), AlertStyle.Cancel) { choosingUnit = false },
            onDismiss = { choosingUnit = false },
        )
    }

    if (choosingLanguage) {
        IosAlert(
            title = stringResource(R.string.settings_language),
            message = null,
            buttons = AppLanguage.entries.map { option ->
                val name = languageName(option)
                AlertButton(if (language == option) "✓  $name" else name) {
                    choosingLanguage = false
                    if (option != language) {
                        AppLanguage.set(context, option)
                        // Android 13+ restarts the screen itself; before that, Neutrino does.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) (context as? android.app.Activity)?.recreate()
                    }
                }
            } + AlertButton(stringResource(R.string.backup_cancel), AlertStyle.Cancel) { choosingLanguage = false },
            onDismiss = { choosingLanguage = false },
        )
    }

    if (lockUnavailable) {
        IosAlert(
            title = stringResource(R.string.settings_app_lock),
            message = stringResource(R.string.settings_app_lock_unavailable),
            buttons = listOf(AlertButton(stringResource(R.string.meters_done), AlertStyle.Cancel) { lockUnavailable = false }),
            onDismiss = { lockUnavailable = false },
        )
    }
}

/** Each language is named in itself, so anyone can find their own. */
@Composable
private fun languageName(language: AppLanguage): String = when (language) {
    AppLanguage.System -> stringResource(R.string.settings_language_system)
    AppLanguage.English -> "English"
    AppLanguage.Bangla -> "বাংলা"
}

@Composable
private fun goalsSummary(settings: AppSettings): String {
    val parts = listOfNotNull(
        settings.carbGoalG?.let { stringResource(R.string.goals_summary_carbs, it) },
        settings.proteinGoalG?.let { stringResource(R.string.goals_summary_protein, it) },
        settings.fatGoalG?.let { stringResource(R.string.goals_summary_fat, it) },
        settings.kcalGoal?.let { stringResource(R.string.goals_summary_kcal, it) },
        settings.waterGoalMl?.let { stringResource(R.string.goals_summary_water, dev.ytosko.neutrino.domain.insights.formatWater(it)) },
    )
    return if (parts.isEmpty()) stringResource(R.string.goals_none) else parts.joinToString(" · ")
}

/** A setting that's on or off: the whole row toggles it. */
@Composable
private fun SwitchRow(icon: Int, title: String, subtitle: String?, checked: Boolean, tint: Tint, onChange: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch) { on ->
                haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onChange(on)
            }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        IconBadge(icon = icon, container = tint.solid, content = Color.White, size = 32.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(bottom = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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
private fun SettingRow(
    icon: Int,
    tint: Tint,
    title: String,
    value: String?,
    external: Boolean = false,
    valueColor: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        IconBadge(icon = icon, container = tint.solid, content = Color.White, size = 32.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            painterResource(if (external) R.drawable.ic_external_link else R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A divider that starts after the row's icon, like iPhone settings. */
@Composable
private fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = Spacing.md + 32.dp + Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
}
