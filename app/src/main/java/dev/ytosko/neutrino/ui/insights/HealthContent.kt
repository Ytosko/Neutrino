package dev.ytosko.neutrino.ui.insights

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import dev.ytosko.neutrino.domain.insights.Slot
import dev.ytosko.neutrino.domain.insights.Period
import java.time.LocalTime
import java.time.LocalDate
import dev.ytosko.neutrino.ui.components.ChartIllustration
import dev.ytosko.neutrino.ui.components.SegmentedControl
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.res.pluralStringResource
import dev.ytosko.neutrino.ui.glucose.relationLabel
import dev.ytosko.neutrino.ui.glucose.LocalGlucoseUnit
import dev.ytosko.neutrino.ui.glucose.bandLabel
import dev.ytosko.neutrino.ui.glucose.bandColor
import dev.ytosko.neutrino.ui.glucose.band
import dev.ytosko.neutrino.ui.glucose.GlucoseBand
import dev.ytosko.neutrino.domain.insights.GlucoseSummary
import dev.ytosko.neutrino.domain.insights.MealRise
import dev.ytosko.neutrino.domain.insights.GlucoseBucket
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.insights.Bucket
import dev.ytosko.neutrino.domain.insights.InsightRange
import dev.ytosko.neutrino.domain.insights.InsightSummary
import dev.ytosko.neutrino.domain.insights.compactGrams
import dev.ytosko.neutrino.domain.insights.compactMl
import dev.ytosko.neutrino.domain.insights.compactNumber
import dev.ytosko.neutrino.domain.food.FoodCategory
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.components.TotalsCard
import dev.ytosko.neutrino.ui.components.mealTypeColors
import dev.ytosko.neutrino.ui.components.mealTypeIcon
import dev.ytosko.neutrino.ui.components.mealTypeLabel
import dev.ytosko.neutrino.ui.food.FoodIcon
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private enum class Macro { Carbs, Protein, Fat }

/**
 * The Health tab: range tabs, totals, then charts you can read by touch. [onOpenDay] jumps to a
 * day on the Days tab.
 */
@Composable
fun HealthContent(viewModel: HealthViewModel, contentPadding: PaddingValues, onOpenDay: (LocalDate) -> Unit) {
    val period by viewModel.period.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val goals by viewModel.goals.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val glucose by viewModel.glucoseSummary.collectAsStateWithLifecycle()
    val mealRises by viewModel.mealRises.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.gutter,
            end = Spacing.gutter,
            top = contentPadding.calculateTopPadding() + Spacing.xs,
            bottom = contentPadding.calculateBottomPadding() + Spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val width = Modifier.widthIn(max = 600.dp).fillMaxWidth()
        item(key = "range") { RangeTabs(period.range, viewModel::setRange, width) }
        item(key = "period") {
            PeriodHeader(
                period = period,
                today = today,
                data = summary?.takeIf { it.period == period },
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onHourly = viewModel::setHourly,
                modifier = width,
            )
        }

        val data = summary
        if (data == null || data.period != period) return@LazyColumn
        val chart = ChartContext(data.period, today, goals)
        val key = "${period.range}-${period.start}-${period.hourly}"
        item(key = "totals") { TotalsCard(data.totals, width) }

        val glucoseData = glucose?.takeIf { it.period == period && !it.isEmpty }
        val rises = mealRises?.takeIf { it.first == period }?.second.orEmpty()
        if (data.isEmpty) {
            if (glucoseData != null) item(key = "glucose-$key") { GlucoseCard(glucoseData, chart, width) }
            if (rises.isNotEmpty()) item(key = "meal-glucose-$key") { MealGlucoseCard(rises, width) }
            item(key = "empty") { EmptyRange(width) }
            return@LazyColumn
        }
        item(key = "calories-$key") { CaloriesCard(data, chart, onOpenDay, width) }
        if (glucoseData != null) item(key = "glucose-$key") { GlucoseCard(glucoseData, chart, width) }
        if (rises.isNotEmpty()) item(key = "meal-glucose-$key") { MealGlucoseCard(rises, width) }
        item(key = "macros-$key") { MacroTrendCard(data, chart, width) }
        item(key = "split-$key") { MacroSplitCard(data, width) }
        item(key = "meals-$key") { CarbsByMealCard(data, width) }
        item(key = "water-$key") { WaterCard(data, chart, width) }
        if (data.topFoods.isNotEmpty()) item(key = "foods-$key") { TopFoodsCard(data, width) }
    }
}

@Composable
private fun RangeTabs(range: InsightRange, onChange: (InsightRange) -> Unit, modifier: Modifier) {
    SegmentedControl(
        options = InsightRange.entries.map {
            stringResource(
                when (it) {
                    InsightRange.Day -> R.string.health_day
                    InsightRange.Week -> R.string.health_week
                    InsightRange.Month -> R.string.health_month
                    InsightRange.Year -> R.string.health_year
                },
            )
        },
        selected = InsightRange.entries.indexOf(range),
        onSelect = { onChange(InsightRange.entries[it]) },
        modifier = modifier,
    )
}

/**
 * ‹ 22 – 28 Sep 2026 › with "Logged on 5 of 7 days" under it. › stops at the period holding today.
 * The Day tab adds the "Hour by hour" switch.
 */
@Composable
private fun PeriodHeader(
    period: Period,
    today: LocalDate,
    data: InsightSummary?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onHourly: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val canGoNext = period.end <= today
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onPrevious() }) {
                Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.health_prev_period))
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    periodTitle(period, today),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                val subtitle = when {
                    data == null -> ""
                    period.range == InsightRange.Day -> pluralStringResource(R.plurals.health_meals_logged, data.meals, data.meals)
                    else -> stringResource(R.string.health_logged_days, data.daysLogged, data.totalDays)
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            IconButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onNext() },
                enabled = canGoNext,
            ) {
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.health_next_period))
            }
        }
        if (period.range == InsightRange.Day) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .toggleable(value = period.hourly, role = Role.Switch) { on ->
                        haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        onHourly(on)
                    }
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.health_hourly), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = period.hourly, onCheckedChange = null)
            }
        }
    }
}

/** What every chart needs besides its own numbers: the period, today, and the daily goals. */
private class ChartContext(val period: Period, val today: LocalDate, val goals: ChartGoals?) {
    val range: InsightRange get() = period.range

    /** Days (Week, Month) and months (Year) show per-day values against daily goals; parts of a day don't. */
    val daily: Boolean get() = range != InsightRange.Day

    /** The bar for now: this hour block, today, or this month. */
    val highlight: Int?
        get() {
            if (!period.contains(today)) return null
            return when (range) {
                InsightRange.Day -> java.time.LocalTime.now().hour / (if (period.hourly) 1 else 6)
                InsightRange.Week, InsightRange.Month -> java.time.temporal.ChronoUnit.DAYS.between(period.start, today).toInt()
                InsightRange.Year -> today.monthValue - 1
            }
        }

    fun goal(pick: (ChartGoals) -> Int?): Double? = if (daily) goals?.let(pick)?.toDouble() else null
}

/** A bar's value: the total for a block or day, the per-day average for a month on the Year tab. */
private fun Bucket.shown(range: InsightRange): Nutrition = if (range == InsightRange.Year) perDayNutrition else nutrition
private fun Bucket.shownWater(range: InsightRange): Int = if (range == InsightRange.Year) perDayWaterMl else waterMl

// ---- Cards ------------------------------------------------------------------------------------

@Composable
private fun CaloriesCard(data: InsightSummary, chart: ChartContext, onOpenDay: (LocalDate) -> Unit, modifier: Modifier) {
    var selected by rememberSaveable(data.period) { mutableStateOf<Int?>(null) }
    val range = data.range
    val values = data.buckets.map { it.shown(range).calories }
    val average = headline(data) { it.calories }
    val bucket = selected?.let(data.buckets::getOrNull)
    ChartCard(title = stringResource(R.string.health_calories), modifier = modifier) {
        if (bucket != null) {
            ReadOut(
                label = bucketLabel(bucket.slot, range),
                value = "${compactNumber(bucket.shown(range).calories)} ${stringResource(R.string.macro_energy)}",
                detail = macroLine(bucket.shown(range)),
                action = if (range != InsightRange.Year) {
                    { TextButton(onClick = { onOpenDay(bucket.start) }) { Text(stringResource(R.string.health_open_day)) } }
                } else {
                    null
                },
            )
        } else {
            ReadOut(
                label = stringResource(headlineLabel(range)),
                value = "${compactNumber(average)} ${stringResource(R.string.macro_energy)}",
                detail = stringResource(R.string.health_touch_hint),
            )
        }
        BarChart(
            values = values,
            color = MaterialTheme.colorScheme.primary,
            selected = selected,
            onSelect = { selected = it },
            average = average.takeIf { it > 0 && chart.daily },
            goal = chart.goal { it.kcal },
            highlight = chart.highlight,
            xLabel = { axisLabel(chart, data.buckets.map { b -> b.slot }, it) },
            description = stringResource(R.string.health_calories_desc, compactNumber(average)),
            bubble = { i -> "${compactNumber(values[i])} kcal" },
        )
    }
}

@Composable
private fun MacroTrendCard(data: InsightSummary, chart: ChartContext, modifier: Modifier) {
    val colors = NeutrinoTheme.colors
    var macro by rememberSaveable { mutableStateOf(Macro.Carbs) }
    var selected by rememberSaveable(data.period) { mutableStateOf<Int?>(null) }
    val range = data.range
    fun pick(n: Nutrition) = when (macro) {
        Macro.Carbs -> n.carbsG
        Macro.Protein -> n.proteinG
        Macro.Fat -> n.fatG
    }
    val color = when (macro) {
        Macro.Carbs -> colors.carbs
        Macro.Protein -> colors.protein
        Macro.Fat -> colors.fat
    }
    val average = headline(data) { pick(it) }
    val bucket = selected?.let(data.buckets::getOrNull)
    val goal = chart.goal {
        when (macro) {
            Macro.Carbs -> it.carbs
            Macro.Protein -> it.protein
            Macro.Fat -> it.fat
        }
    }
    ChartCard(title = stringResource(R.string.health_macros), modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Macro.entries.forEach { option ->
                FilterChip(
                    selected = option == macro,
                    onClick = { macro = option },
                    label = { Text(stringResource(macroName(option))) },
                )
            }
        }
        if (bucket != null) {
            ReadOut(label = bucketLabel(bucket.slot, range), value = compactGrams(pick(bucket.shown(range))), valueColor = color)
        } else {
            ReadOut(label = stringResource(headlineLabel(range)), value = compactGrams(average), valueColor = color)
        }
        BarChart(
            values = data.buckets.map { pick(it.shown(range)) },
            color = color,
            selected = selected,
            onSelect = { selected = it },
            average = average.takeIf { it > 0 && chart.daily },
            goal = goal,
            highlight = chart.highlight,
            xLabel = { axisLabel(chart, data.buckets.map { b -> b.slot }, it) },
            description = stringResource(R.string.health_macro_desc, stringResource(macroName(macro)), compactGrams(average)),
            bubble = { i -> compactGrams(pick(data.buckets[i].shown(range))) },
        )
    }
}

@Composable
private fun MacroSplitCard(data: InsightSummary, modifier: Modifier) {
    val colors = NeutrinoTheme.colors
    var selected by rememberSaveable(data.period) { mutableStateOf<Int?>(null) }
    val (carbKcal, proteinKcal, fatKcal) = data.macroKcal
    val kcal = listOf(carbKcal, proteinKcal, fatKcal)
    val grams = listOf(data.totals.carbsG, data.totals.proteinG, data.totals.fatG)
    val sliceColors = listOf(colors.carbs, colors.protein, colors.fat)
    val total = kcal.sum()
    fun percent(i: Int) = if (total > 0) (kcal[i] / total * 100).roundToInt() else 0

    ChartCard(title = stringResource(R.string.health_split), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            DonutChart(
                slices = kcal.mapIndexed { i, v -> Slice(v, sliceColors[i]) },
                selected = selected,
                onSelect = { selected = it },
                size = 148.dp,
                description = stringResource(R.string.health_split_desc, percent(0), percent(1), percent(2)),
            ) {
                val i = selected
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (i != null) "${percent(i)}%" else compactNumber(total),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (i != null) sliceColors[i] else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (i != null) stringResource(macroName(Macro.entries[i])) else stringResource(R.string.macro_energy),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Macro.entries.forEachIndexed { i, macro ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(sliceColors[i], CircleShape))
                        Spacer(Modifier.width(Spacing.xs))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(macroName(macro)), style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${compactGrams(grams[i])} · ${percent(i)}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.health_split_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CarbsByMealCard(data: InsightSummary, modifier: Modifier) {
    val order = listOf(MealType.Breakfast, MealType.Lunch, MealType.Snack, MealType.Dinner)
    val totalCarbs = order.sumOf { data.byMealType.getValue(it).carbsG }
    ChartCard(title = stringResource(R.string.health_carbs_by_meal), modifier = modifier) {
        order.forEach { type ->
            val carbs = data.byMealType.getValue(type).carbsG
            val (color, soft) = mealTypeColors(type)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                IconBadge(mealTypeIcon(type), container = soft, content = color, size = 36.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(mealTypeLabel(type), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text(
                            "${compactGrams(carbs)} · ${if (totalCarbs > 0) (carbs / totalCarbs * 100).roundToInt() else 0}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ShareBar(if (totalCarbs > 0) (carbs / totalCarbs).toFloat() else 0f, color)
                }
            }
        }
    }
}

@Composable
private fun WaterCard(data: InsightSummary, chart: ChartContext, modifier: Modifier) {
    val colors = NeutrinoTheme.colors
    var selected by rememberSaveable(data.period) { mutableStateOf<Int?>(null) }
    val range = data.range
    val average = if (range == InsightRange.Day) data.waterMl else data.waterPerDay
    val values = data.buckets.map { it.shownWater(range) }
    val bucket = selected?.let(data.buckets::getOrNull)
    ChartCard(title = stringResource(R.string.health_water), modifier = modifier) {
        if (bucket != null) {
            ReadOut(label = bucketLabel(bucket.slot, range), value = compactMl(bucket.shownWater(range)), valueColor = colors.water)
        } else {
            ReadOut(label = stringResource(headlineLabel(range)), value = compactMl(average), valueColor = colors.water)
        }
        BarChart(
            values = values.map { it.toDouble() },
            color = colors.water,
            selected = selected,
            onSelect = { selected = it },
            average = average.toDouble().takeIf { it > 0 && chart.daily },
            goal = chart.goal { it.waterMl },
            highlight = chart.highlight,
            xLabel = { axisLabel(chart, data.buckets.map { b -> b.slot }, it) },
            height = 140.dp,
            description = stringResource(R.string.health_water_desc, compactMl(average)),
            bubble = { i -> compactMl(values[i]) },
        )
    }
}

/** Average glucose per bar, time in the target range, and averages by meal mark. */
@Composable
private fun GlucoseCard(data: GlucoseSummary, chart: ChartContext, modifier: Modifier) {
    var selected by rememberSaveable(data.period) { mutableStateOf<Int?>(null) }
    val bucket = selected?.let(data.buckets::getOrNull)
    val glucoseUnit = LocalGlucoseUnit.current
    val unit = glucoseUnit.label
    fun bandOf(value: Double?) = value?.let { band(it, data.low, data.high) } ?: GlucoseBand.InRange
    ChartCard(title = stringResource(R.string.glucose_title), modifier = modifier) {
        if (bucket != null) {
            ReadOut(
                label = bucketLabel(bucket.slot, data.range, perDay = false),
                value = bucket.average?.let { "${glucoseUnit.format(it)} $unit" } ?: "–",
                detail = pluralStringResource(R.plurals.health_glucose_readings, bucket.readings, bucket.readings),
                valueColor = if (bucket.average != null) bandColor(bandOf(bucket.average)) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ReadOut(
                label = stringResource(R.string.health_glucose_average),
                value = data.average?.let { "${glucoseUnit.format(it)} $unit" } ?: "–",
                detail = pluralStringResource(R.plurals.health_glucose_readings, data.readings, data.readings),
                valueColor = bandColor(bandOf(data.average)),
            )
        }
        BarChart(
            values = data.buckets.map { b -> b.average?.let(glucoseUnit::fromMmol) ?: 0.0 },
            color = bandColor(GlucoseBand.InRange),
            selected = selected,
            onSelect = { selected = it },
            average = data.average?.let(glucoseUnit::fromMmol),
            highlight = chart.highlight,
            xLabel = { axisLabel(chart, data.buckets.map { b -> b.slot }, it) },
            height = 140.dp,
            description = stringResource(R.string.health_glucose_desc, data.average?.let(glucoseUnit::format) ?: "–", unit),
            bubble = { i -> data.buckets[i].average?.let { "${glucoseUnit.format(it)} $unit" } ?: "–" },
        )

        Text(
            stringResource(R.string.health_glucose_time_in_range),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        val shares = listOf(
            Triple(GlucoseBand.Low, data.belowShare, bandColor(GlucoseBand.Low)),
            Triple(GlucoseBand.InRange, data.inRangeShare, bandColor(GlucoseBand.InRange)),
            Triple(GlucoseBand.High, data.aboveShare, bandColor(GlucoseBand.High)),
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(12.dp).clip(MaterialTheme.shapes.small),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            shares.filter { it.second > 0 }.forEach { (_, share, color) ->
                Box(Modifier.weight(share.toFloat()).fillMaxHeight().background(color))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            shares.forEach { (band, share, color) ->
                Column {
                    Text("${(share * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium, color = color)
                    Text(bandLabel(band), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            stringResource(R.string.health_glucose_target, glucoseUnit.format(data.low), glucoseUnit.format(data.high), unit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (data.byRelation.size > 1) {
            Text(
                stringResource(R.string.health_glucose_by_meal),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            data.byRelation.forEach { (relation, average) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(relationLabel(relation), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${glucoseUnit.format(average)} $unit", style = MaterialTheme.typography.labelLarge, color = bandColor(bandOf(average)))
                }
            }
        }
    }
}

/**
 * How much glucose went up (or down) from before a meal to about 2 hours after, per meal, as the
 * user's own averages. Plain numbers, no judgement.
 */
@Composable
private fun MealGlucoseCard(rises: List<MealRise>, modifier: Modifier) {
    val unit = LocalGlucoseUnit.current
    ChartCard(title = stringResource(R.string.health_meal_glucose_title), modifier = modifier) {
        Text(
            stringResource(R.string.health_meal_glucose_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val biggest = rises.maxOf { kotlin.math.abs(it.averageRise) }.takeIf { it > 0 } ?: 1.0
        rises.forEach { rise ->
            val up = rise.averageRise >= 0
            val change = (if (up) "+" else "−") + unit.format(kotlin.math.abs(rise.averageRise))
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rise.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        "$change ${unit.label}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (up) bandColor(GlucoseBand.High) else bandColor(GlucoseBand.InRange),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ShareBar(
                        fraction = (kotlin.math.abs(rise.averageRise) / biggest).toFloat(),
                        color = if (up) bandColor(GlucoseBand.High) else bandColor(GlucoseBand.InRange),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        pluralStringResource(R.plurals.health_meal_glucose_times, rise.times, rise.times),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopFoodsCard(data: InsightSummary, modifier: Modifier) {
    ChartCard(title = stringResource(R.string.health_top_foods), modifier = modifier) {
        data.topFoods.forEach { food ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FoodIcon(FoodCategory.fromKey(food.category), size = 36.dp)
                Text(
                    food.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.health_times, food.times),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyRange(modifier: Modifier) {
    Column(modifier = modifier.padding(vertical = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        ChartIllustration()
        Spacer(Modifier.size(Spacing.md))
        Text(stringResource(R.string.health_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.health_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

// ---- Pieces -----------------------------------------------------------------------------------

@Composable
private fun ChartCard(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** The numbers above a chart: the touched bar's, or the average. */
@Composable
private fun ReadOut(
    label: String,
    value: String,
    detail: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    action: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = valueColor)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action?.invoke()
    }
}

@Composable
private fun macroLine(n: Nutrition): String =
    "${stringResource(R.string.macro_carbs)} ${compactGrams(n.carbsG)} · ${stringResource(R.string.macro_protein)} ${compactGrams(n.proteinG)} · " +
        "${stringResource(R.string.macro_fat)} ${compactGrams(n.fatG)}"

private fun macroName(macro: Macro): Int = when (macro) {
    Macro.Carbs -> R.string.macro_carbs
    Macro.Protein -> R.string.macro_protein
    Macro.Fat -> R.string.macro_fat
}

/** Day: the day's total. Week, month, year: the average per logged day. */
private fun headlineLabel(range: InsightRange): Int =
    if (range == InsightRange.Day) R.string.health_day_total else R.string.health_avg_day

private fun headline(data: InsightSummary, value: (Nutrition) -> Double): Double =
    if (data.range == InsightRange.Day) value(data.totals) else data.perLoggedDay(value(data.totals))

private val HOUR = DateTimeFormatter.ofPattern("h a")

/** "Today", "Yesterday", "Fri, 25 Sep 2026"; "21 – 27 Sep 2026"; "September 2026"; "2026". */
@Composable
private fun periodTitle(period: Period, today: LocalDate): String {
    val start = period.start
    val last = period.end.minusDays(1)
    return when (period.range) {
        InsightRange.Day -> when (start) {
            today -> stringResource(R.string.home_title)
            today.minusDays(1) -> stringResource(R.string.home_yesterday)
            else -> start.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
        }
        InsightRange.Week -> when {
            start.year != last.year -> "${start.format(DAY_MONTH_YEAR)} – ${last.format(DAY_MONTH_YEAR)}"
            start.month != last.month -> "${start.format(SHORT_DATE)} – ${last.format(DAY_MONTH_YEAR)}"
            else -> "${start.dayOfMonth} – ${last.format(DAY_MONTH_YEAR)}"
        }
        InsightRange.Month -> start.format(DateTimeFormatter.ofPattern("LLLL yyyy"))
        InsightRange.Year -> start.year.toString()
    }
}

private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM")
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy")

/** The touched bar: "6 AM – 12 PM", "Fri, 25 Sep", or "September 2026 · per day". */
@Composable
private fun bucketLabel(slot: Slot, range: InsightRange, perDay: Boolean = true): String = when (range) {
    // The last block ends at 11:59 PM, not "12 AM", so it reads as the same day.
    InsightRange.Day -> "${LocalTime.of(slot.fromHour, 0).format(HOUR)} – " +
        if (slot.toHour == 24) LocalTime.of(23, 59).format(DateTimeFormatter.ofPattern("h:mm a")) else LocalTime.of(slot.toHour, 0).format(HOUR)
    InsightRange.Week, InsightRange.Month -> slot.start.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
    InsightRange.Year -> {
        val month = slot.start.format(DateTimeFormatter.ofPattern("LLLL yyyy"))
        if (perDay) stringResource(R.string.health_per_day, month) else month
    }
}

/**
 * Labels under the bars: the start of each 6-hour block (every 6th hour when hourly), weekday
 * initials, days 1, 8, 15, 22, 29 of a month, month initials of a year. The bar for now always
 * gets its label (in bold); month labels right next to it step aside.
 */
private fun axisLabel(chart: ChartContext, slots: List<Slot>, index: Int): String? {
    val slot = slots.getOrNull(index) ?: return null
    val highlight = chart.highlight
    return when (chart.range) {
        InsightRange.Day -> if (slot.fromHour % 6 == 0) LocalTime.of(slot.fromHour, 0).format(HOUR) else null
        InsightRange.Week -> slot.start.format(DateTimeFormatter.ofPattern("EEEEE"))
        InsightRange.Month -> when {
            index == highlight -> slot.start.dayOfMonth.toString()
            highlight != null && kotlin.math.abs(index - highlight) < 3 -> null
            index % 7 == 0 -> slot.start.dayOfMonth.toString()
            else -> null
        }
        InsightRange.Year -> slot.start.format(DateTimeFormatter.ofPattern("LLLLL"))
    }
}
