package dev.ytosko.neutrino.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.goals.WeighIn
import dev.ytosko.neutrino.domain.goals.GoalMath
import dev.ytosko.neutrino.domain.goals.HeightUnit
import dev.ytosko.neutrino.domain.goals.PlanUnit
import dev.ytosko.neutrino.domain.goals.Sex
import dev.ytosko.neutrino.domain.goals.WeightUnit
import dev.ytosko.neutrino.ui.medicine.TimeDialog
import dev.ytosko.neutrino.ui.theme.Spacing
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Reads a number typed with either a dot or a comma. */
internal fun parseNumber(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

internal fun formatNumber(v: Double): String =
    if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

/** "72.5 kg" or "159.8 lb". */
internal fun weightText(kg: Double, unit: WeightUnit): String =
    if (unit == WeightUnit.Kg) "${formatNumber((kg * 10).roundToInt() / 10.0)} kg" else "${formatNumber((GoalMath.kgToLb(kg) * 10).roundToInt() / 10.0)} lb"

/** "170 cm" or "5 ft 7 in". */
internal fun heightText(cm: Double, unit: HeightUnit): String =
    if (unit == HeightUnit.Cm) {
        "${cm.roundToInt()} cm"
    } else {
        val (ft, inch) = GoalMath.feetInches(cm)
        "$ft ft $inch in"
    }

@Composable
private fun EditorDialog(
    title: String,
    canSave: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) { content() } },
        confirmButton = { TextButton(onClick = onSave, enabled = canSave) { Text(stringResource(R.string.goals_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun NumberField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    suffix: String,
    decimal: Boolean,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    // The first field takes focus so the keyboard is up as soon as the editor opens.
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) {
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
    OutlinedTextField(
        value = value,
        onValueChange = { t -> onValue(t.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) }.take(6)) },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.fillMaxWidth().then(if (autoFocus) Modifier.focusRequester(focus) else Modifier),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segments(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label(option)) }
        }
    }
}

/** Date of birth, from a calendar; the age then keeps itself up to date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthDateDialog(birthDate: LocalDate?, onSave: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val utc = java.time.ZoneOffset.UTC
    val state = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = (birthDate ?: today.minusYears(30)).atStartOfDay(utc).toInstant().toEpochMilli(),
        yearRange = (today.year - 110)..(today.year - 13),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                java.time.Instant.ofEpochMilli(utcTimeMillis).atZone(utc).toLocalDate().let { it <= today.minusYears(13) && it >= today.minusYears(110) }
        },
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onSave(java.time.Instant.ofEpochMilli(it).atZone(utc).toLocalDate()) } },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(R.string.goals_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    ) {
        androidx.compose.material3.DatePicker(
            state = state,
            title = { Text(stringResource(R.string.physique_birth_date), modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
        )
    }
}

@Composable
internal fun SexDialog(sex: Sex?, onSave: (Sex) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.physique_sex)) },
        text = {
            Column {
                Sex.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = option == sex, role = Role.RadioButton) { onSave(option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == sex, onClick = null)
                        Text(sexLabel(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spacing.sm))
                    }
                }
                Text(
                    stringResource(R.string.physique_sex_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
internal fun sexLabel(sex: Sex): String = stringResource(if (sex == Sex.Male) R.string.physique_male else R.string.physique_female)

@Composable
internal fun HeightDialog(cm: Double?, unit: HeightUnit, onSave: (Double, HeightUnit) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(unit) }
    var cmText by remember { mutableStateOf(cm?.roundToInt()?.toString().orEmpty()) }
    val start = cm?.let { GoalMath.feetInches(it) }
    var ftText by remember { mutableStateOf(start?.first?.toString().orEmpty()) }
    var inText by remember { mutableStateOf(start?.second?.toString().orEmpty()) }
    val value: Double? = if (chosen == HeightUnit.Cm) {
        parseNumber(cmText)
    } else {
        ftText.toIntOrNull()?.let { ft -> GoalMath.cm(ft, inText.toIntOrNull() ?: 0) }
    }?.takeIf { it in 100.0..250.0 }
    EditorDialog(stringResource(R.string.physique_height), value != null, { onSave(value!!, chosen) }, onDismiss) {
        Segments(HeightUnit.entries, chosen, { if (it == HeightUnit.Cm) "cm" else "ft / in" }) { new ->
            // Carry the number across when switching.
            val current = value
            if (current != null) {
                cmText = current.roundToInt().toString()
                val (ft, inch) = GoalMath.feetInches(current)
                ftText = ft.toString()
                inText = inch.toString()
            }
            chosen = new
        }
        if (chosen == HeightUnit.Cm) {
            NumberField(cmText, { cmText = it }, stringResource(R.string.physique_height), "cm", decimal = true)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                NumberField(ftText, { ftText = it }, stringResource(R.string.physique_feet), "ft", decimal = false, modifier = Modifier.weight(1f))
                NumberField(inText, { inText = it }, stringResource(R.string.physique_inches), "in", decimal = false, modifier = Modifier.weight(1f), autoFocus = false)
            }
        }
    }
}

@Composable
internal fun WeightDialog(title: String, kg: Double?, unit: WeightUnit, onSave: (Double, WeightUnit) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(unit) }
    fun shown(k: Double, u: WeightUnit) = formatNumber(((if (u == WeightUnit.Kg) k else GoalMath.kgToLb(k)) * 10).roundToInt() / 10.0)
    var text by remember { mutableStateOf(kg?.let { shown(it, unit) }.orEmpty()) }
    val value = parseNumber(text)?.let { if (chosen == WeightUnit.Kg) it else GoalMath.lbToKg(it) }?.takeIf { it in 25.0..350.0 }
    EditorDialog(title, value != null, { onSave(value!!, chosen) }, onDismiss) {
        Segments(WeightUnit.entries, chosen, { if (it == WeightUnit.Kg) "kg" else "lb" }) { new ->
            value?.let { text = shown(it, new) }
            chosen = new
        }
        NumberField(text, { text = it }, title, if (chosen == WeightUnit.Kg) "kg" else "lb", decimal = true)
    }
}

@Composable
internal fun planUnitLabel(unit: PlanUnit): String = stringResource(
    when (unit) {
        PlanUnit.Days -> R.string.physique_days
        PlanUnit.Months -> R.string.physique_months
        PlanUnit.Years -> R.string.physique_years
    },
)

@Composable
internal fun PlanDialog(length: Int?, unit: PlanUnit, onSave: (Int, PlanUnit) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(unit) }
    var text by remember { mutableStateOf(length?.toString().orEmpty()) }
    val max = when (chosen) {
        PlanUnit.Days -> 3_650
        PlanUnit.Months -> 120
        PlanUnit.Years -> 10
    }
    val value = text.toIntOrNull()?.takeIf { it in 1..max }
    EditorDialog(stringResource(R.string.physique_plan), value != null, { onSave(value!!, chosen) }, onDismiss) {
        Text(
            stringResource(R.string.physique_plan_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Segments(PlanUnit.entries, chosen, { planUnitLabel(it) }) { chosen = it }
        NumberField(text, { text = it }, stringResource(R.string.physique_plan), planUnitLabel(chosen).lowercase(), decimal = false)
    }
}

/** The weekly weigh-in: on or off, which day and what time. */
@Composable
internal fun WeighInDialog(weighIn: WeighIn, onSave: (WeighIn) -> Unit, onDismiss: () -> Unit) {
    var on by remember { mutableStateOf(weighIn.on) }
    var day by remember { mutableStateOf(weighIn.day) }
    var minute by remember { mutableStateOf(weighIn.minute) }
    var pickingTime by remember { mutableStateOf(false) }
    val time = LocalTime.of(minute / 60, minute % 60)
    EditorDialog(stringResource(R.string.physique_weigh_in), true, { onSave(WeighIn(on, day, minute)) }, onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = on, role = Role.Switch) { on = it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.physique_weigh_in_remind), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = null)
        }
        if (on) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(
                        selected = d.value == day,
                        onClick = { day = d.value },
                        label = { Text(d.getDisplayName(TextStyle.SHORT, androidx.compose.ui.platform.LocalConfiguration.current.locales[0])) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { pickingTime = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.physique_weigh_in_time), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    time.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (pickingTime) {
        TimeDialog(initial = time, onDismiss = { pickingTime = false }, onConfirm = {
            minute = it.hour * 60 + it.minute
            pickingTime = false
        })
    }
}
