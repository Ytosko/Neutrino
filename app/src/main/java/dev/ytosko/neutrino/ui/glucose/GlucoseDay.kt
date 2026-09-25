package dev.ytosko.neutrino.ui.glucose

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.glucose.GlucoseEntity
import dev.ytosko.neutrino.data.glucose.GlucoseRelation
import dev.ytosko.neutrino.data.glucose.relationEnum
import dev.ytosko.neutrino.data.glucose.isManual
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The day's blood glucose readings, oldest first. Every value carries its band in words as well as
 * colour, and tapping a reading opens it for changing the meal mark or time.
 */
@Composable
fun GlucoseDayCard(
    readings: List<GlucoseEntity>,
    range: ClosedFloatingPointRange<Double>,
    isToday: Boolean,
    onOpen: (GlucoseEntity) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            IconBadge(
                R.drawable.ic_activity,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.tertiary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.glucose_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (readings.isEmpty()) {
                        stringResource(if (isToday) R.string.glucose_none_today else R.string.glucose_none_day)
                    } else {
                        pluralStringResource(
                            R.plurals.glucose_day_summary,
                            readings.size,
                            readings.size,
                            glucoseText(readings.map { it.mmolPerL }.average()),
                            glucoseUnitLabel(),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = onAdd) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = stringResource(R.string.glucose_add_title))
            }
        }
        // A busy day would push the meals far down: show the latest few until asked for all.
        var expanded by rememberSaveable { mutableStateOf(false) }
        val shown = if (expanded || readings.size <= COLLAPSED_COUNT) readings else readings.takeLast(COLLAPSED_COUNT)
        shown.forEachIndexed { index, reading ->
            if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
            GlucoseRow(reading, range, onClick = { onOpen(reading) })
        }
        if (readings.size > COLLAPSED_COUNT) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
            ) {
                Text(
                    if (expanded) stringResource(R.string.glucose_show_less)
                    else stringResource(R.string.glucose_show_all, readings.size),
                )
            }
        } else if (readings.isNotEmpty()) {
            Spacer(Modifier.size(Spacing.xs))
        }
    }
}

@Composable
private fun GlucoseRow(reading: GlucoseEntity, range: ClosedFloatingPointRange<Double>, onClick: () -> Unit) {
    val band = band(reading.mmolPerL, range.start, range.endInclusive)
    val color = bandColor(band)
    val time = remember(reading.measuredAtEpochMs) { shortTime(reading.measuredAtEpochMs) }
    val relation = relationLabel(reading.relationEnum)
    val bandText = bandLabel(band)
    val description = stringResource(R.string.glucose_reading_desc, reading.valueText(), time, relation, bandText, glucoseUnitLabel()) +
        if (reading.timeEstimated) ". " + stringResource(R.string.glucose_estimated_short) else ""
    val openLabel = stringResource(R.string.glucose_edit_title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = openLabel) { onClick(); true }
            }
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            time,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(relation, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (reading.timeEstimated) {
                Icon(
                    painterResource(R.drawable.ic_clock),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (!reading.syncedToHealthConnect) {
                Text(stringResource(R.string.home_not_synced), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(reading.valueText(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = color)
                Text(
                    " " + glucoseUnitLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Text(bandText, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

/**
 * Add a reading by hand ([reading] null), or change one: its meal mark and time, and its value
 * only if it was typed in by hand. A meter's value is never editable: it is exactly what the
 * meter measured.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlucoseEditDialog(
    reading: GlucoseEntity?,
    range: ClosedFloatingPointRange<Double>,
    /** Where a new reading starts: now, or the same time of day on a past day. */
    newReadingTime: Instant = Instant.now(),
    onSave: (mmolPerL: Double?, GlucoseRelation, Instant) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val unit = LocalGlucoseUnit.current
    val zone = remember { ZoneId.systemDefault() }
    val key = reading?.id ?: "new"
    val original = remember(key) { Instant.ofEpochMilli(reading?.measuredAtEpochMs ?: newReadingTime.toEpochMilli()).atZone(zone) }
    val originalRelation = reading?.relationEnum ?: GlucoseRelation.General
    var relation by remember(key) { mutableStateOf(originalRelation) }
    var at by remember(key) { mutableStateOf(original) }
    var relationMenu by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val valueEditable = reading == null || reading.isManual
    var valueText by remember(key) { mutableStateOf(reading?.takeIf { it.isManual }?.let { unit.format(it.mmolPerL) } ?: "") }
    // Typed in the user's unit; meters measure roughly 0.6–33.3 mmol/L (10–600 mg/dL).
    val typedMmol = valueText.replace(',', '.').toDoubleOrNull()?.let(unit::toMmol)?.takeIf { it in 0.6..33.3 }
    val valueError = valueEditable && valueText.isNotBlank() && typedMmol == null
    val inFuture = at.toInstant().isAfter(Instant.now().plusSeconds(60))
    // Only the minute is shown, so an untouched time keeps its seconds.
    val timeChanged = at.withSecond(0).withNano(0) != original.withSecond(0).withNano(0)
    val valueChanged = valueEditable && typedMmol != null && (reading == null || kotlin.math.abs(typedMmol - reading.mmolPerL) > 1e-6)
    val changed = reading == null || relation != originalRelation || timeChanged || valueChanged
    val canSave = changed && !inFuture && (!valueEditable || typedMmol != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (reading == null) R.string.glucose_add_title else R.string.glucose_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (valueEditable) {
                    OutlinedTextField(
                        value = valueText,
                        onValueChange = { valueText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.glucose_value_label, unit.label)) },
                        isError = valueError,
                        supportingText = if (valueError) {
                            { Text(stringResource(R.string.glucose_value_error, unit.format(0.6), unit.format(33.3), unit.label)) }
                        } else {
                            null
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (reading != null && !valueEditable) {
                val band = band(reading.mmolPerL, range.start, range.endInclusive)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        reading.valueText(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = bandColor(band),
                    )
                    Text(
                        " " + glucoseUnitLabel() + " · " + bandLabel(band),
                        style = MaterialTheme.typography.titleSmall,
                        color = bandColor(band),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                }
                if (reading?.timeEstimated == true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Icon(painterResource(R.drawable.ic_clock), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.glucose_estimated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                ExposedDropdownMenuBox(expanded = relationMenu, onExpandedChange = { relationMenu = it }) {
                    OutlinedTextField(
                        value = relationLabel(relation),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.glucose_relation)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = relationMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = relationMenu, onDismissRequest = { relationMenu = false }) {
                        GlucoseRelation.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(relationLabel(option)) },
                                onClick = {
                                    relation = option
                                    relationMenu = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    WhenTile(R.drawable.ic_calendar, stringResource(R.string.glucose_date), at.format(DateTimeFormatter.ofPattern("EEE, d MMM")), { pickDate = true }, Modifier.weight(1f))
                    WhenTile(R.drawable.ic_clock, stringResource(R.string.glucose_time), at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), { pickTime = true }, Modifier.weight(1f))
                }
                if (inFuture) {
                    Text(stringResource(R.string.glucose_future), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (reading != null) {
                    TextButton(onClick = onDelete) {
                        Icon(painterResource(R.drawable.ic_trash), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.glucose_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(if (valueEditable) typedMmol else null, relation, if (timeChanged) at.toInstant() else original.toInstant()) },
                enabled = canSave,
            ) { Text(stringResource(R.string.glucose_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) } },
    )

    if (pickDate) {
        val today = LocalDate.now(zone)
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = at.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        val date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        at = ZonedDateTime.of(date, at.toLocalTime(), zone)
                    }
                    pickDate = false
                }) { Text(stringResource(R.string.review_time_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.home_cancel)) } },
        ) { DatePicker(state = pickerState) }
    }

    if (pickTime) {
        val pickerState = rememberTimePickerState(at.hour, at.minute, DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    at = ZonedDateTime.of(at.toLocalDate(), LocalTime.of(pickerState.hour, pickerState.minute), zone)
                    pickTime = false
                }) { Text(stringResource(R.string.review_time_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.home_cancel)) } },
            text = { TimePicker(state = pickerState) },
        )
    }
}

@Composable
private fun WhenTile(icon: Int, label: String, value: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val COLLAPSED_COUNT = 3

private fun shortTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
