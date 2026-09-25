package dev.ytosko.neutrino.ui.insights

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
fun HealthContent(viewModel: HealthViewModel, contentPadding: PaddingValues, onOpenDay: (java.time.LocalDate) -> Unit) {
    val range by viewModel.range.collectAsStateWithLifecycle()
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
        item(key = "range") { RangeTabs(range, viewModel::setRange, width) }

        val data = summary
        if (data == null || data.range != range) return@LazyColumn
        item(key = "caption") { RangeCaption(data, width) }
        item(key = "totals") { TotalsCard(data.totals, width) }

        val glucoseData = glucose?.takeIf { it.range == range && !it.isEmpty }
        if (data.isEmpty) {
            if (glucoseData != null) item(key = "glucose-$range") { GlucoseCard(glucoseData, width) }
        val rises = mealRises?.takeIf { it.first == range }?.second.orEmpty()
        if (rises.isNotEmpty()) item(key = "meal-glucose-$range") { MealGlucoseCard(rises, width) }
            item(key = "empty") { EmptyRange(width) }
            return@LazyColumn
        }
        item(key = "calories-$range") { CaloriesCard(data, onOpenDay, width) }
        if (glucoseData != null) item(key = "glucose-$range") { GlucoseCard(glucoseData, width) }
        item(key = "macros-$range") { MacroTrendCard(data, width) }
        item(key = "split-$range") { MacroSplitCard(data, width) }
        item(key = "meals-$range") { CarbsByMealCard(data, width) }
        item(key = "water-$range") { WaterCard(data, width) }
        if (data.topFoods.isNotEmpty()) item(key = "foods-$range") { TopFoodsCard(data, width) }
    }
}

@Composable
private fun RangeTabs(range: InsightRange, onChange: (InsightRange) -> Unit, modifier: Modifier) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        InsightRange.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == range,
                onClick = { onChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, InsightRange.entries.size),
                modifier = Modifier.heightIn(min = 48.dp),
                icon = {},
            ) {
                Text(
                    stringResource(
                        when (option) {
                            InsightRange.Day -> R.string.health_day
                            InsightRange.Week -> R.string.health_week
                            InsightRange.Month -> R.string.health_month
                            InsightRange.Year -> R.string.health_year
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun RangeCaption(data: InsightSummary, modifier: Modifier) {
    val first = data.buckets.first().start
    val last = data.buckets.last().end.minusDays(1)
    val dates = "${first.format(SHORT_DATE)} – ${minOf(last, java.time.LocalDate.now()).format(SHORT_DATE)}"
    Column(modifier = modifier) {
        Text(
            stringResource(
                when (data.range) {
                    InsightRange.Day -> R.string.health_last_days
                    InsightRange.Week -> R.string.health_last_weeks
                    InsightRange.Month -> R.string.health_last_months
                    InsightRange.Year -> R.string.health_last_years
                },
                data.range.count,
            ),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.health_caption, dates, data.daysLogged, data.totalDays),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Cards ------------------------------------------------------------------------------------

@Composable
private fun CaloriesCard(data: InsightSummary, onOpenDay: (java.time.LocalDate) -> Unit, modifier: Modifier) {
    var selected by rememberSaveable(data.range) { mutableStateOf<Int?>(null) }
    val values = data.buckets.map { it.nutrition.calories }
    val average = averageOfLogged(data.buckets) { it.nutrition.calories }
    val bucket = selected?.let(data.buckets::getOrNull)
    ChartCard(title = stringResource(R.string.health_calories), modifier = modifier) {
        if (bucket != null) {
            ReadOut(
                label = bucketLabel(bucket, data.range),
                value = "${compactNumber(bucket.nutrition.calories)} ${stringResource(R.string.macro_energy)}",
                detail = macroLine(bucket.nutrition),
                action = if (data.range == InsightRange.Day) {
                    { TextButton(onClick = { onOpenDay(bucket.start) }) { Text(stringResource(R.string.health_open_day)) } }
                } else {
                    null
                },
            )
        } else {
            ReadOut(
                label = stringResource(averageLabel(data.range)),
                value = "${compactNumber(average)} ${stringResource(R.string.macro_energy)}",
                detail = stringResource(R.string.health_touch_hint),
            )
        }
        BarChart(
            values = values,
            color = MaterialTheme.colorScheme.primary,
            selected = selected,
            onSelect = { selected = it },
            average = average.takeIf { it > 0 },
            xLabel = { axisLabel(data, it) },
            description = stringResource(R.string.health_calories_desc, compactNumber(average)),
        )
    }
}

@Composable
private fun MacroTrendCard(data: InsightSummary, modifier: Modifier) {
    val colors = NeutrinoTheme.colors
    var macro by rememberSaveable { mutableStateOf(Macro.Carbs) }
    var selected by rememberSaveable(data.range) { mutableStateOf<Int?>(null) }
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
    val average = averageOfLogged(data.buckets) { pick(it.nutrition) }
    val bucket = selected?.let(data.buckets::getOrNull)
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
            ReadOut(label = bucketLabel(bucket, data.range), value = compactGrams(pick(bucket.nutrition)), valueColor = color)
        } else {
            ReadOut(label = stringResource(averageLabel(data.range)), value = compactGrams(average), valueColor = color)
        }
        BarChart(
            values = data.buckets.map { pick(it.nutrition) },
            color = color,
            selected = selected,
            onSelect = { selected = it },
            average = average.takeIf { it > 0 },
            xLabel = { axisLabel(data, it) },
            description = stringResource(R.string.health_macro_desc, stringResource(macroName(macro)), compactGrams(average)),
        )
    }
}

@Composable
private fun MacroSplitCard(data: InsightSummary, modifier: Modifier) {
    val colors = NeutrinoTheme.colors
    var selected by rememberSaveable(data.range) { mutableStateOf<Int?>(null) }
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
private fun WaterCard(data: InsightSummary, modifier: Modifier) {
    val colors = NeutrinoTheme.colors
    var selected by rememberSaveable(data.range) { mutableStateOf<Int?>(null) }
    val average = averageOfLogged(data.buckets, { it.waterMl > 0 }) { it.waterMl.toDouble() }
    val bucket = selected?.let(data.buckets::getOrNull)
    ChartCard(title = stringResource(R.string.health_water), modifier = modifier) {
        if (bucket != null) {
            ReadOut(label = bucketLabel(bucket, data.range), value = compactMl(bucket.waterMl), valueColor = colors.water)
        } else {
            ReadOut(label = stringResource(averageLabel(data.range)), value = compactMl(average.roundToInt()), valueColor = colors.water)
        }
        BarChart(
            values = data.buckets.map { it.waterMl.toDouble() },
            color = colors.water,
            selected = selected,
            onSelect = { selected = it },
            average = average.takeIf { it > 0 },
            xLabel = { axisLabel(data, it) },
            height = 140.dp,
            description = stringResource(R.string.health_water_desc, compactMl(average.roundToInt())),
        )
    }
}

/** Average glucose per bar, time in the target range, and averages by meal mark. */
@Composable
private fun GlucoseCard(data: GlucoseSummary, modifier: Modifier) {
    var selected by rememberSaveable(data.range) { mutableStateOf<Int?>(null) }
    val bucket = selected?.let(data.buckets::getOrNull)
    val glucoseUnit = LocalGlucoseUnit.current
    val unit = glucoseUnit.label
    fun bandOf(value: Double?) = value?.let { band(it, data.low, data.high) } ?: GlucoseBand.InRange
    ChartCard(title = stringResource(R.string.glucose_title), modifier = modifier) {
        if (bucket != null) {
            ReadOut(
                label = bucketLabel(bucket.toBucket(), data.range),
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
            xLabel = { axisLabel(data.range, data.buckets.map { b -> b.start }, it) },
            height = 140.dp,
            description = stringResource(R.string.health_glucose_desc, data.average?.let(glucoseUnit::format) ?: "–", unit),
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

private fun GlucoseBucket.toBucket() = Bucket(start, end, Nutrition.ZERO, 0, 0, 0)

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
        IconBadge(R.drawable.ic_chart_column, container = NeutrinoTheme.colors.indigo.container, content = NeutrinoTheme.colors.indigo.content, size = 64.dp)
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
        border = CardDefaults.outlinedCardBorder(),
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

private fun averageLabel(range: InsightRange): Int = when (range) {
    InsightRange.Day -> R.string.health_avg_day
    InsightRange.Week -> R.string.health_avg_week
    InsightRange.Month -> R.string.health_avg_month
    InsightRange.Year -> R.string.health_avg_year
}

/** Mean over the bars that have data, so empty days or weeks don't pull it down. */
private fun averageOfLogged(
    buckets: List<Bucket>,
    hasData: (Bucket) -> Boolean = { it.meals > 0 },
    value: (Bucket) -> Double,
): Double {
    val logged = buckets.filter(hasData)
    return if (logged.isEmpty()) 0.0 else logged.sumOf(value) / logged.size
}

private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM")

private fun bucketLabel(bucket: Bucket, range: InsightRange): String = when (range) {
    InsightRange.Day -> bucket.start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    InsightRange.Week -> "${bucket.start.format(SHORT_DATE)} – ${bucket.end.minusDays(1).format(SHORT_DATE)}"
    InsightRange.Month -> bucket.start.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    InsightRange.Year -> bucket.start.year.toString()
}

/** A few labels under the bars so they don't crowd: every 7th day, every 4th week, every month, every year. */
private fun axisLabel(data: InsightSummary, index: Int): String? = axisLabel(data.range, data.buckets.map { it.start }, index)

private fun axisLabel(range: InsightRange, starts: List<java.time.LocalDate>, index: Int): String? {
    val start = starts.getOrNull(index) ?: return null
    val fromEnd = starts.lastIndex - index
    return when (range) {
        InsightRange.Day -> if (fromEnd % 7 == 0) start.dayOfMonth.toString() else null
        InsightRange.Week -> if (fromEnd % 4 == 0) start.format(SHORT_DATE) else null
        InsightRange.Month -> start.format(DateTimeFormatter.ofPattern("MMMMM"))
        InsightRange.Year -> "'" + (start.year % 100).toString().padStart(2, '0')
    }
}
