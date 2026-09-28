package dev.ytosko.neutrino.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.domain.goals.GoalMath
import dev.ytosko.neutrino.domain.goals.WeightEntry
import dev.ytosko.neutrino.domain.goals.WeightUnit
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** How far back the chart goes. [Week] is offered only when there are several weigh-ins that week. */
private enum class WeightRange(val days: Long) { Week(7), Month(30), HalfYear(182), Year(365) }

/** One bar: its label, its weight (the last weigh-in in that slot) and the day it's from. */
private data class Slot(val label: String?, val kg: Double?, val date: LocalDate?)

/** Days for a month, weeks for 6 months, months for a year; each slot shows its last weigh-in. */
private fun slots(entries: List<WeightEntry>, range: WeightRange, today: LocalDate, locale: Locale): List<Slot> {
    val byDate = entries.groupBy { it.date }.mapValues { (_, list) -> list.maxBy { it.epochMs } }
    fun lastIn(from: LocalDate, to: LocalDate) = byDate.filterKeys { !it.isBefore(from) && !it.isAfter(to) }.maxByOrNull { it.key }
    return when (range) {
        WeightRange.Week -> (6 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            Slot(day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale), byDate[day]?.kg, day)
        }
        WeightRange.Month -> (29 downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            Slot(if (back % 7 == 0) day.dayOfMonth.toString() else null, byDate[day]?.kg, day)
        }
        WeightRange.HalfYear -> {
            val thisWeek = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            (25 downTo 0).map { back ->
                val start = thisWeek.minusWeeks(back.toLong())
                val found = lastIn(start, start.plusDays(6))
                val label = if (start.dayOfMonth <= 7) start.month.getDisplayName(java.time.format.TextStyle.SHORT, locale) else null
                Slot(label, found?.value?.kg, found?.key)
            }
        }
        WeightRange.Year -> (11 downTo 0).map { back ->
            val month = today.withDayOfMonth(1).minusMonths(back.toLong())
            val found = lastIn(month, month.plusMonths(1).minusDays(1))
            Slot(month.month.getDisplayName(java.time.format.TextStyle.NARROW, locale), found?.value?.kg, found?.key)
        }
    }
}

/**
 * Settings → Daily goals → Current weight: the latest weight, how it has changed and how far the
 * target is, a chart for the last month, 6 months or year, and every weigh-in. The history also
 * goes to the AI with goal suggestions.
 */
@Composable
fun WeightScreen(
    viewModel: GoalsViewModel,
    openWeight: kotlinx.coroutines.flow.MutableStateFlow<Boolean>,
    onBack: () -> Unit,
) {
    val entries by viewModel.weights.collectAsStateWithLifecycle()
    val physique by viewModel.physique.collectAsStateWithLifecycle()
    val unit = physique.weightUnit
    val locale = LocalConfiguration.current.locales[0]
    // Nothing picked yet: the shortest range on offer (7 days when there is one, else 1 month).
    var chosenRange by rememberSaveable { mutableStateOf<WeightRange?>(null) }
    // 7 days only makes sense with more than one weigh-in in the last week.
    val weekFrom = LocalDate.now().minusDays(6)
    val ranges = if (entries.count { !it.date.isBefore(weekFrom) } > 1) WeightRange.entries else WeightRange.entries - WeightRange.Week
    val range = chosenRange?.takeIf { it in ranges } ?: ranges.first()
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<WeightEntry?>(null) }
    var editingEntry by remember { mutableStateOf<WeightEntry?>(null) }
    var lifted by remember { mutableStateOf<Pair<WeightEntry, androidx.compose.ui.geometry.Rect>?>(null) }
    val blur by androidx.compose.animation.core.animateDpAsState(if (lifted != null) 14.dp else 0.dp, androidx.compose.animation.core.tween(200), label = "blur")
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var selected by remember(range) { mutableStateOf<Int?>(null) }
    val today = LocalDate.now()
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    // The weigh-in reminder opens this page ready to add today's weight.
    val wantsWeight by openWeight.collectAsStateWithLifecycle()
    LaunchedEffect(wantsWeight) {
        if (wantsWeight) {
            openWeight.value = false
            adding = true
        }
    }

    SetupScaffold(
        title = stringResource(R.string.weight_title),
        onBack = onBack,
        modifier = Modifier.blur(blur),
        bottomBar = {
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.weight_add), modifier = Modifier.padding(start = Spacing.sm))
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            val latest = entries.maxByOrNull { it.epochMs }
            // Now: the latest weight, the change over the chosen range and what's left to the target.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (latest == null) {
                    Text(
                        stringResource(R.string.weight_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.weight_latest, latest.date.format(dates)).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(weightText(latest.kg, unit), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    val since = entries.filter { it.epochMs >= Instant.now().minus(range.days, ChronoUnit.DAYS).toEpochMilli() }
                    val first = since.minByOrNull { it.epochMs }
                    if (first != null && first.id != latest.id) {
                        val change = latest.kg - first.kg
                        Text(
                            stringResource(
                                if (change <= 0) R.string.weight_change_down else R.string.weight_change_up,
                                weightText(abs(change), unit),
                                first.date.format(dates),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (change <= 0) NeutrinoTheme.colors.good else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    physique.targetKg?.let { target ->
                        val left = latest.kg - target
                        Text(
                            if (abs(left) < 0.1) {
                                stringResource(R.string.weight_at_target)
                            } else {
                                stringResource(R.string.weight_to_go, weightText(abs(left), unit), weightText(target, unit))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ranges.forEachIndexed { index, r ->
                    SegmentedButton(
                        selected = r == range,
                        onClick = { chosenRange = r },
                        shape = SegmentedButtonDefaults.itemShape(index, ranges.size),
                    ) {
                        Text(
                            stringResource(
                                when (r) {
                                    WeightRange.Week -> R.string.weight_range_week
                                    WeightRange.Month -> R.string.weight_range_month
                                    WeightRange.HalfYear -> R.string.weight_range_half
                                    WeightRange.Year -> R.string.weight_range_year
                                },
                            ),
                        )
                    }
                }
            }

            val chartSlots = slots(entries, range, today, locale)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                val pick = selected?.let { chartSlots.getOrNull(it) }
                Text(
                    if (pick?.kg != null && pick.date != null) {
                        "${weightText(pick.kg, unit)} · ${pick.date.format(dates)}"
                    } else {
                        stringResource(R.string.weight_chart_hint)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (pick?.kg != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WeightChart(
                    slots = chartSlots,
                    target = physique.targetKg,
                    unit = unit,
                    selected = selected,
                    onSelect = { selected = it },
                    color = NeutrinoTheme.colors.sky.solid,
                    description = stringResource(R.string.weight_title),
                )
            }

            if (entries.isNotEmpty()) {
                GoalGroup(stringResource(R.string.weight_history)) {
                    val newestFirst = entries.sortedByDescending { it.epochMs }
                    newestFirst.forEachIndexed { index, e ->
                        if (index > 0) GoalDivider()
                        val previous = newestFirst.getOrNull(index + 1)
                        // Tap to edit; touch and hold for the iPhone-style menu (Edit, Delete).
                        var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { bounds = it.boundsInWindow() }
                                .graphicsLayer { alpha = if (lifted?.first?.id == e.id) 0f else 1f }
                                .combinedClickable(
                                    role = Role.Button,
                                    onClick = { editingEntry = e },
                                    onLongClick = {
                                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        lifted = e to bounds
                                    },
                                ),
                        ) { WeighInRow(e, previous, unit, dates) }
                    }
                }
            }
        }
    }

    if (adding) {
        AddWeighInDialog(
            start = physique.weightKg,
            unit = unit,
            onSave = { kg, newUnit, date ->
                if (newUnit != unit) viewModel.setPhysique(physique.copy(weightUnit = newUnit))
                // Today: now. Another day: midday, so it sorts sensibly and keeps its date in any zone nearby.
                val at = if (date == today) Instant.now() else date.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant()
                viewModel.setWeight(kg, at)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
    lifted?.let { (e, rect) ->
        val previous = entries.sortedByDescending { it.epochMs }.let { list -> list.getOrNull(list.indexOfFirst { it.id == e.id } + 1) }
        dev.ytosko.neutrino.ui.components.LiftedContextMenu(
            bounds = rect,
            shape = RoundedCornerShape(12.dp),
            actions = listOf(
                dev.ytosko.neutrino.ui.components.MenuAction(stringResource(R.string.weight_edit), R.drawable.ic_pencil) {
                    lifted = null
                    editingEntry = e
                },
                dev.ytosko.neutrino.ui.components.MenuAction(stringResource(R.string.weight_delete), R.drawable.ic_trash, destructive = true) {
                    lifted = null
                    deleting = e
                },
            ),
            onDismiss = { lifted = null },
        ) { WeighInRow(e, previous, unit, dates) }
    }
    editingEntry?.let { old ->
        AddWeighInDialog(
            start = null,
            unit = unit,
            editing = old,
            onSave = { kg, newUnit, date ->
                if (newUnit != unit) viewModel.setPhysique(physique.copy(weightUnit = newUnit))
                // Same day: keep its time. Another day: midday.
                val at = if (date == old.date) Instant.ofEpochMilli(old.epochMs) else date.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant()
                viewModel.editWeight(old, kg, at)
                editingEntry = null
            },
            onDismiss = { editingEntry = null },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.weight_delete_title)) },
            text = { Text("${weightText(e.kg, unit)} · ${e.date.format(dates)}") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeWeight(e.id)
                    deleting = null
                }) { Text(stringResource(R.string.weight_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }
}

/** One weigh-in: its day, the change from the one before, and the weight. */
@Composable
private fun WeighInRow(e: WeightEntry, previous: WeightEntry?, unit: WeightUnit, dates: DateTimeFormatter) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(e.date.format(dates), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (previous != null) {
            val d = e.kg - previous.kg
            if (abs(d) >= 0.05) {
                Text(
                    (if (d > 0) "+" else "−") + weightText(abs(d), unit),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d < 0) NeutrinoTheme.colors.good else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = Spacing.md),
                )
            }
        }
        Text(weightText(e.kg, unit), style = MaterialTheme.typography.titleSmall)
    }
}

/** Weight and the day it was measured (today unless changed). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWeighInDialog(
    start: Double?,
    unit: WeightUnit,
    onSave: (Double, WeightUnit, LocalDate) -> Unit,
    onDismiss: () -> Unit,
    editing: WeightEntry? = null,
) {
    var date by remember { mutableStateOf(editing?.date ?: LocalDate.now()) }
    var pickingDate by remember { mutableStateOf(false) }
    // Starts empty (the last weight is a hint), so typing a new weight doesn't add to the old one.
    WeightDialog(
        title = stringResource(if (editing != null) R.string.weight_edit else R.string.weight_add),
        kg = editing?.kg,
        hint = start?.let { stringResource(R.string.weight_last_hint, weightText(it, unit)) },
        unit = unit,
        onSave = { kg, u -> onSave(kg, u, date) },
        onDismiss = onDismiss,
        extra = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { pickingDate = true }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.weight_date), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = Spacing.sm))
                Text(
                    if (date == LocalDate.now()) stringResource(R.string.weight_today) else date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
    if (pickingDate) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text(stringResource(R.string.goals_save)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.backup_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/**
 * Weight bars on a scale that fits the weights (not from zero, so a 2 kg change shows), with the
 * target as a dashed line. Tap or slide to read a bar.
 */
@Composable
private fun WeightChart(
    slots: List<Slot>,
    target: Double?,
    unit: WeightUnit,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    color: Color,
    description: String,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val targetColor = NeutrinoTheme.colors.good
    val select by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(selected)
    fun shown(kg: Double) = if (unit == WeightUnit.Kg) kg else GoalMath.kgToLb(kg)
    val values = slots.mapNotNull { it.kg }.map(::shown) + listOfNotNull(target?.let(::shown))
    val low = values.minOrNull()?.let { floor(it - 1) } ?: 0.0
    val high = values.maxOrNull()?.let { ceil(it + 1) } ?: 1.0
    val count = slots.size.coerceAtLeast(1)
    val gutter = 36.dp

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = description }
            .pointerInput(count) {
                val plot = { size.width - gutter.toPx() }
                fun at(x: Float) = (x / (plot() / count)).toInt().takeIf { x in 0f..plot() }?.coerceIn(0, count - 1)
                detectTapGestures { o -> at(o.x).let { select(if (it == current) null else it) } }
            }
            .pointerInput(count) {
                val plot = { size.width - gutter.toPx() }
                fun at(x: Float) = (x.coerceIn(0f, plot() - 1f) / (plot() / count)).toInt().coerceIn(0, count - 1)
                detectHorizontalDragGestures(onDragStart = { select(at(it.x)) }) { change, _ ->
                    select(at(change.position.x))
                    change.consume()
                }
            },
    ) {
        val plotWidth = size.width - gutter.toPx()
        val labelSpace = 18.dp.toPx()
        val plotHeight = size.height - labelSpace
        val slot = plotWidth / count
        val barWidth = (slot * 0.6f).coerceAtMost(28.dp.toPx())
        val radius = CornerRadius(barWidth / 2)
        fun y(v: Double) = (plotHeight - (v - low) / (high - low) * plotHeight).toFloat()

        // Three grid lines with their weights on the right.
        listOf(low, (low + high) / 2, high).forEach { v ->
            val yy = y(v)
            drawLine(grid, Offset(0f, yy), Offset(plotWidth, yy), strokeWidth = 1f)
            val text = measurer.measure(String.format(Locale.getDefault(), "%.0f", v), labelStyle)
            drawText(text, topLeft = Offset(plotWidth + 6.dp.toPx(), (yy - text.size.height / 2f).coerceIn(0f, plotHeight - text.size.height)))
        }
        slots.forEachIndexed { i, s ->
            val x = slot * i + (slot - barWidth) / 2
            s.kg?.let { kg ->
                val top = y(shown(kg)).coerceAtMost(plotHeight - barWidth)
                val alpha = if (selected == null || selected == i) 1f else 0.35f
                drawRoundRect(color.copy(alpha = alpha), topLeft = Offset(x, top), size = Size(barWidth, plotHeight - top), cornerRadius = radius)
            }
            s.label?.let {
                val text = measurer.measure(it, labelStyle)
                drawText(text, topLeft = Offset((slot * i + slot / 2 - text.size.width / 2f).coerceIn(0f, plotWidth - text.size.width), plotHeight + 4.dp.toPx()))
            }
        }
        target?.let {
            val yy = y(shown(it))
            drawLine(
                targetColor, Offset(0f, yy), Offset(plotWidth, yy), strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
            )
        }
    }
}
