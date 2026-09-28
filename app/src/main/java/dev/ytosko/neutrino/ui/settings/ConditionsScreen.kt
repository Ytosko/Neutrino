package dev.ytosko.neutrino.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.domain.GlucoseUnit
import dev.ytosko.neutrino.domain.goals.Conditions
import dev.ytosko.neutrino.domain.goals.HormoneUnit
import dev.ytosko.neutrino.domain.goals.LiverLog
import dev.ytosko.neutrino.domain.goals.LiverTest
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.glucose.LocalGlucoseUnit
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal enum class ConditionKind { Diabetes, BloodPressure, Thyroid, Liver }

private fun Conditions.has(kind: ConditionKind): Boolean = when (kind) {
    ConditionKind.Diabetes -> diabetes
    ConditionKind.BloodPressure -> bloodPressure
    ConditionKind.Thyroid -> thyroid
    ConditionKind.Liver -> liver
}

private fun Conditions.with(kind: ConditionKind): Conditions = when (kind) {
    ConditionKind.Diabetes -> copy(diabetes = true)
    ConditionKind.BloodPressure -> copy(bloodPressure = true)
    ConditionKind.Thyroid -> copy(thyroid = true)
    ConditionKind.Liver -> copy(liver = true)
}

/** Takes a condition off, with its values. */
private fun Conditions.without(kind: ConditionKind): Conditions = when (kind) {
    ConditionKind.Diabetes -> copy(diabetes = false, avgGlucoseMmol = null)
    ConditionKind.BloodPressure -> copy(bloodPressure = false, systolic = null, diastolic = null)
    ConditionKind.Thyroid -> copy(thyroid = false, tsh = null, ft3 = null, ft4 = null, thyroidTestDay = null)
    ConditionKind.Liver -> copy(
        liver = false, cap = null, kpa = null, alt = null, ast = null,
        steatosisGrade = null, fibrosisGrade = null, liverTestDay = null,
    )
}

@Composable
internal fun conditionName(kind: ConditionKind): String = stringResource(
    when (kind) {
        ConditionKind.Diabetes -> R.string.conditions_diabetes
        ConditionKind.BloodPressure -> R.string.conditions_bp
        ConditionKind.Thyroid -> R.string.conditions_thyroid
        ConditionKind.Liver -> R.string.conditions_liver
    },
)

internal fun conditionIcon(kind: ConditionKind): Int = when (kind) {
    ConditionKind.Diabetes -> R.drawable.ic_activity
    ConditionKind.BloodPressure -> R.drawable.ic_heart_pulse
    ConditionKind.Thyroid -> R.drawable.ic_pill
    ConditionKind.Liver -> R.drawable.ic_droplet
}

@Composable
internal fun conditionColor(kind: ConditionKind): Color = when (kind) {
    ConditionKind.Diabetes -> NeutrinoTheme.colors.glucose
    ConditionKind.BloodPressure -> NeutrinoTheme.colors.rose.solid
    ConditionKind.Thyroid -> NeutrinoTheme.colors.violet.solid
    ConditionKind.Liver -> NeutrinoTheme.colors.amber.solid
}

internal fun fibrosisLabel(grade: Int): String = if (grade <= 1) "F0–F1" else "F$grade"

/** One line under the name: the values that matter, or that there are none yet. */
@Composable
private fun conditionSummary(kind: ConditionKind, c: Conditions, meterAverage: Double?, unit: GlucoseUnit, liver: List<LiverTest>): String {
    val none = stringResource(R.string.conditions_no_values)
    return when (kind) {
        ConditionKind.Diabetes -> c.avgGlucoseMmol?.let { stringResource(R.string.conditions_avg_value, unit.format(it), unit.label) }
            ?: meterAverage?.let { stringResource(R.string.conditions_meter_value, unit.format(it), unit.label) }
            ?: none
        ConditionKind.BloodPressure ->
            if (c.systolic != null && c.diastolic != null) "${c.systolic}/${c.diastolic} mmHg" else none
        ConditionKind.Thyroid -> listOfNotNull(
            c.tsh?.let { "TSH ${formatNumber(it)}" },
            c.ft3?.let { "FT3 ${formatNumber(it)}" },
            c.ft4?.let { "FT4 ${formatNumber(it)}" },
        ).joinToString(" · ").ifEmpty { none }
        ConditionKind.Liver -> {
            // The latest FibroScan grades and the latest ALT, from the log.
            val scan = LiverLog.latestScan(liver)
            val blood = LiverLog.latestBlood(liver)
            listOfNotNull(
                scan?.steatosis?.let { "S$it" },
                scan?.fibrosis?.let(::fibrosisLabel),
                blood?.alt?.let { "ALT $it" },
            ).joinToString(" · ").ifEmpty { stringResource(R.string.liver_no_results) }
        }
    }
}

/**
 * Settings → Daily goals → My conditions: the conditions the person has, one row each with its
 * key values. Tapping one opens its own editor with a Save button; "Add a condition" offers the
 * rest. Used only for goal suggestions.
 */
@Composable
fun ConditionsScreen(viewModel: GoalsViewModel, onOpenLiver: () -> Unit, onBack: () -> Unit) {
    val saved by viewModel.savedConditions.collectAsStateWithLifecycle()
    val liverTests by viewModel.liverTests.collectAsStateWithLifecycle()
    val meterAverage by viewModel.meterAverage.collectAsStateWithLifecycle()
    val unit = LocalGlucoseUnit.current
    var editing by remember { mutableStateOf<ConditionKind?>(null) }
    var picking by remember { mutableStateOf(false) }
    val c = saved
    val added = c?.let { s -> ConditionKind.entries.filter { s.has(it) } }.orEmpty()
    val missing = ConditionKind.entries - added.toSet()

    SetupScaffold(
        title = stringResource(R.string.conditions_title),
        subtitle = stringResource(R.string.conditions_body),
        onBack = onBack,
        bottomBar = if (c != null && missing.isNotEmpty()) {
            {
                Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(painterResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.conditions_add), modifier = Modifier.padding(start = Spacing.sm))
                }
            }
        } else {
            null
        },
    ) {
        if (c == null) return@SetupScaffold
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            if (added.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    ColorIcon(R.drawable.ic_heart_pulse, NeutrinoTheme.colors.rose.solid, 44.dp)
                    Text(
                        stringResource(R.string.conditions_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                GoalGroup(stringResource(R.string.conditions_yours)) {
                    added.forEachIndexed { index, kind ->
                        if (index > 0) GoalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 60.dp)
                                // Fatty liver has its own page with the log of results.
                                .clickable(role = Role.Button) { if (kind == ConditionKind.Liver) onOpenLiver() else editing = kind }
                                .padding(start = 14.dp, end = Spacing.sm, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            ColorIcon(conditionIcon(kind), conditionColor(kind))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(conditionName(kind), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    conditionSummary(kind, c, meterAverage, unit, liverTests),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                painterResource(R.drawable.ic_chevron_right),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.conditions_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.conditions_add)) },
            text = {
                Column {
                    missing.forEach { kind ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button) {
                                    picking = false
                                    if (kind == ConditionKind.Liver) {
                                        viewModel.setConditions(c!!.with(kind))
                                        onOpenLiver()
                                    } else {
                                        editing = kind
                                    }
                                }
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            ColorIcon(conditionIcon(kind), conditionColor(kind))
                            Text(conditionName(kind), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }

    val kind = editing
    if (kind != null && c != null) {
        ConditionSheet(
            kind = kind,
            start = c,
            isNew = !c.has(kind),
            meterAverage = meterAverage,
            unit = unit,
            onSave = {
                viewModel.setConditions(it.with(kind))
                editing = null
            },
            onRemove = {
                viewModel.setConditions(c.without(kind))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** One condition's values, saved together with its own Save button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConditionSheet(
    kind: ConditionKind,
    start: Conditions,
    isNew: Boolean,
    meterAverage: Double?,
    unit: GlucoseUnit,
    onSave: (Conditions) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var c by remember { mutableStateOf(start) }
    val decimals = { t: String -> t.filter { it.isDigit() || it == '.' || it == ',' }.take(6) }
    val digits = { t: String -> t.filter(Char::isDigit).take(4) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ColorIcon(conditionIcon(kind), conditionColor(kind), 32.dp)
                Text(conditionName(kind), style = MaterialTheme.typography.titleLarge)
            }
            when (kind) {
                ConditionKind.Diabetes -> {
                    var text by remember { mutableStateOf(c.avgGlucoseMmol?.let { unit.format(it) }.orEmpty()) }
                    val fromMeter = text.isEmpty() && meterAverage != null
                    Field(
                        value = if (fromMeter) unit.format(meterAverage!!) else text,
                        onValue = { t ->
                            text = decimals(t)
                            c = c.copy(avgGlucoseMmol = parseNumber(text)?.let(unit::toMmol)?.takeIf { it in 2.0..35.0 })
                        },
                        label = stringResource(R.string.conditions_avg_glucose),
                        suffix = unit.label,
                        decimal = true,
                        supporting = if (fromMeter) stringResource(R.string.conditions_from_meter) else null,
                    )
                }

                ConditionKind.BloodPressure -> {
                    var sys by remember { mutableStateOf(c.systolic?.toString().orEmpty()) }
                    var dia by remember { mutableStateOf(c.diastolic?.toString().orEmpty()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Field(sys, { t ->
                            sys = digits(t).take(3)
                            c = c.copy(systolic = sys.toIntOrNull()?.takeIf { it in 60..260 })
                        }, stringResource(R.string.conditions_bp_top), "mmHg", Modifier.weight(1f))
                        Field(dia, { t ->
                            dia = digits(t).take(3)
                            c = c.copy(diastolic = dia.toIntOrNull()?.takeIf { it in 30..160 })
                        }, stringResource(R.string.conditions_bp_bottom), "mmHg", Modifier.weight(1f))
                    }
                    Note(stringResource(R.string.conditions_bp_note))
                }

                ConditionKind.Thyroid -> {
                    var tsh by remember { mutableStateOf(c.tsh?.let(::formatNumber).orEmpty()) }
                    var ft3 by remember { mutableStateOf(c.ft3?.let(::formatNumber).orEmpty()) }
                    var ft4 by remember { mutableStateOf(c.ft4?.let(::formatNumber).orEmpty()) }
                    val pmol = c.hormoneUnit == HormoneUnit.Pmol
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        HormoneUnit.entries.forEachIndexed { index, u ->
                            SegmentedButton(
                                selected = u == c.hormoneUnit,
                                onClick = { c = c.copy(hormoneUnit = u) },
                                shape = SegmentedButtonDefaults.itemShape(index, HormoneUnit.entries.size),
                            ) { Text(if (u == HormoneUnit.Pmol) "pmol/L" else "pg/mL · ng/dL", maxLines = 1) }
                        }
                    }
                    Field(tsh, { t ->
                        tsh = decimals(t)
                        c = c.copy(tsh = parseNumber(tsh)?.takeIf { it in 0.0..200.0 })
                    }, "TSH", "mIU/L", decimal = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Field(ft3, { t ->
                            ft3 = decimals(t)
                            c = c.copy(ft3 = parseNumber(ft3)?.takeIf { it in 0.0..100.0 })
                        }, "FT3", if (pmol) "pmol/L" else "pg/mL", Modifier.weight(1f), decimal = true)
                        Field(ft4, { t ->
                            ft4 = decimals(t)
                            c = c.copy(ft4 = parseNumber(ft4)?.takeIf { it in 0.0..200.0 })
                        }, "FT4", if (pmol) "pmol/L" else "ng/dL", Modifier.weight(1f), decimal = true)
                    }
                    TestDateRow(c.thyroidTestDay) { c = c.copy(thyroidTestDay = it) }
                    Note(stringResource(R.string.conditions_thyroid_note))
                }

                ConditionKind.Liver -> Unit // Its own page: LiverScreen.
            }
            Button(
                onClick = { onSave(c) },
                enabled = isNew || c != start,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.goals_save)) }
            if (!isNew) {
                OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.conditions_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
internal fun steatosisWord(grade: Int): String = stringResource(
    when (grade) {
        0 -> R.string.liver_s0
        1 -> R.string.liver_s1
        2 -> R.string.liver_s2
        else -> R.string.liver_s3
    },
)

@Composable
internal fun fibrosisWord(grade: Int): String = stringResource(
    when (grade) {
        1 -> R.string.liver_f1
        2 -> R.string.liver_f2
        3 -> R.string.liver_f3
        else -> R.string.liver_f4
    },
)

/** Shows the grade the numbers give; without numbers, chips to pick it from the report. */
@Composable
internal fun GradePicker(
    title: String,
    grades: List<Int>,
    label: (Int) -> String,
    describe: @Composable (Int) -> String,
    fromNumbers: Int?,
    picked: Int?,
    onPick: (Int?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (fromNumbers != null) {
            Text(
                stringResource(R.string.liver_from_numbers, "${label(fromNumbers)} · ${describe(fromNumbers)}"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                grades.forEach { g ->
                    FilterChip(
                        selected = g == picked,
                        onClick = { onPick(if (g == picked) null else g) },
                        label = { Text("${label(g)} · ${describe(g)}") },
                    )
                }
            }
        }
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "Test date": optional, picked from a calendar (past dates only). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestDateRow(epochDay: Long?, onChange: (Long?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val date = epochDay?.let(LocalDate::ofEpochDay)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) { picking = true }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(
            stringResource(R.string.conditions_test_date),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = Spacing.sm),
        )
        Text(
            date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: stringResource(R.string.physique_not_set),
            style = MaterialTheme.typography.bodyMedium,
            color = if (date == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
    if (picking) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() })
                    picking = false
                }) { Text(stringResource(R.string.goals_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (date != null) onChange(null)
                    picking = false
                }) { Text(stringResource(if (date != null) R.string.conditions_test_clear else R.string.backup_cancel)) }
            },
        ) {
            DatePicker(
                state = state,
                title = { Text(stringResource(R.string.conditions_test_date), modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            )
        }
    }
}
