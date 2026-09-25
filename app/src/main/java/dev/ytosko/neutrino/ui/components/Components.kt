package dev.ytosko.neutrino.ui.components

import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.clickable
import dev.ytosko.neutrino.ui.theme.Tint
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.insights.compactGrams
import dev.ytosko.neutrino.domain.insights.compactNumber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.res.stringResource
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing

/** The app logo: white mark on a coral rounded tile. Decorative by default. */
@Composable
fun NeutrinoLogo(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(NeutrinoTheme.colors.brand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_neutrino_mark),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.74f),
        )
    }
}

/** A tinted rounded square holding an icon, used in lists and cards. */
@Composable
fun IconBadge(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 44.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/**
 * One number in the totals row. With a daily goal, the tile's border fills clockwise from the
 * top as the day's total approaches the goal, and closes in a stronger line once it's reached.
 * Tapping shows the goal figures in place of the label for a few seconds, so the tile never
 * changes size.
 */
@Composable
fun MacroStat(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    /** e.g. "122 of 150 g · 81%", read by screen readers. */
    goal: String? = null,
    /** e.g. "81% · 150g", shown on tap. */
    goalShort: String? = null,
    /** 0..1+ toward the goal; null without a goal. */
    progress: Float? = null,
) {
    var showGoal by remember { mutableStateOf(false) }
    LaunchedEffect(showGoal) {
        if (showGoal) {
            delay(3_000)
            showGoal = false
        }
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .then(if (progress != null) Modifier.goalRing(progress, color, color.copy(alpha = 0.16f)) else Modifier)
            .then(if (goalShort != null) Modifier.clickable(onClickLabel = goal) { showGoal = !showGoal } else Modifier)
            .heightIn(min = 64.dp)
            .padding(vertical = Spacing.sm, horizontal = Spacing.xs)
            .clearAndSetSemantics { contentDescription = listOfNotNull(label, value, goal).joinToString(", ") },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = if (showGoal && goalShort != null) goalShort else label,
            style = MaterialTheme.typography.labelMedium,
            color = if (showGoal) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Draws the tile's border as a progress ring: a faint track all round, and the progress in
 * [color] from the top centre, clockwise. At the goal the ring closes and gets thicker.
 */
private fun Modifier.goalRing(progress: Float, color: Color, track: Color): Modifier = drawWithContent {
    drawContent()
    val done = progress >= 1f
    val stroke = (if (done) 3.5.dp else 2.5.dp).toPx()
    val inset = stroke / 2
    val radius = 16.dp.toPx() - inset
    val path = roundedRectFromTop(size.width, size.height, radius, inset)
    drawPath(path, track, style = Stroke(width = 2.5.dp.toPx()))
    val fraction = progress.coerceIn(0f, 1f)
    if (fraction <= 0f) return@drawWithContent
    val measure = PathMeasure().apply { setPath(path, false) }
    val part = Path()
    measure.getSegment(0f, measure.length * fraction, part, true)
    drawPath(part, color, style = Stroke(width = stroke, cap = if (done) StrokeCap.Butt else StrokeCap.Round))
}

/** A rounded rectangle traced clockwise from the middle of its top edge. */
private fun roundedRectFromTop(w: Float, h: Float, r: Float, inset: Float): Path = Path().apply {
    val l = inset
    val t = inset
    val rr = w - inset
    val b = h - inset
    moveTo((l + rr) / 2, t)
    lineTo(rr - r, t)
    arcTo(Rect(rr - 2 * r, t, rr, t + 2 * r), -90f, 90f, false)
    lineTo(rr, b - r)
    arcTo(Rect(rr - 2 * r, b - 2 * r, rr, b), 0f, 90f, false)
    lineTo(l + r, b)
    arcTo(Rect(l, b - 2 * r, l + 2 * r, b), 90f, 90f, false)
    lineTo(l, t + r)
    arcTo(Rect(l, t, l + 2 * r, t + 2 * r), 180f, 90f, false)
    close()
}

/** The day's (or range's) carbs, protein, fat and calories, with goal rings when goals are set. */
@Composable
fun TotalsCard(
    totals: Nutrition?,
    modifier: Modifier = Modifier,
    carbGoalG: Int? = null,
    proteinGoalG: Int? = null,
    fatGoalG: Int? = null,
    kcalGoal: Int? = null,
) {
    val colors = NeutrinoTheme.colors
    val n = totals ?: Nutrition.ZERO
    @Composable
    fun goalText(value: Double, goal: Int?, unit: String): String? = goal?.let {
        stringResource(R.string.goal_progress, value.roundToInt(), it, unit, (value / it * 100).roundToInt())
    }
    fun goalShort(value: Double, goal: Int?, unit: String): String? = goal?.let {
        "${(value / it * 100).roundToInt()}% · $it$unit"
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            MacroStat(
                compactGrams(n.carbsG), stringResource(R.string.macro_carbs), colors.carbs, Modifier.weight(1f),
                goal = goalText(n.carbsG, carbGoalG, "g"), goalShort = goalShort(n.carbsG, carbGoalG, "g"), progress = carbGoalG?.let { (n.carbsG / it).toFloat() },
            )
            MacroStat(
                compactGrams(n.proteinG), stringResource(R.string.macro_protein), colors.protein, Modifier.weight(1f),
                goal = goalText(n.proteinG, proteinGoalG, "g"), goalShort = goalShort(n.proteinG, proteinGoalG, "g"), progress = proteinGoalG?.let { (n.proteinG / it).toFloat() },
            )
            MacroStat(
                compactGrams(n.fatG), stringResource(R.string.macro_fat), colors.fat, Modifier.weight(1f),
                goal = goalText(n.fatG, fatGoalG, "g"), goalShort = goalShort(n.fatG, fatGoalG, "g"), progress = fatGoalG?.let { (n.fatG / it).toFloat() },
            )
            MacroStat(
                compactNumber(n.calories), stringResource(R.string.macro_energy), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f),
                goal = goalText(n.calories, kcalGoal, "kcal"), goalShort = goalShort(n.calories, kcalGoal, ""), progress = kcalGoal?.let { (n.calories / it).toFloat() },
            )
        }
    }
}

/** Icon + title + body row used for feature/benefit lists. */
@Composable
fun FeatureRow(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tint: Tint = NeutrinoTheme.colors.coral,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(icon = icon, container = tint.container, content = tint.content)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
fun mealTypeLabel(type: MealType): String = stringResource(
    when (type) {
        MealType.Breakfast -> R.string.meal_breakfast
        MealType.Lunch -> R.string.meal_lunch
        MealType.Dinner -> R.string.meal_dinner
        MealType.Snack -> R.string.meal_snack
    },
)

/** Each meal has its own colour so it's recognisable at a glance (always shown with its name). */
@Composable
fun mealTypeColors(type: MealType): Pair<Color, Color> {
    val colors = NeutrinoTheme.colors
    return when (type) {
        MealType.Breakfast -> colors.carbs to colors.carbsContainer
        MealType.Lunch -> colors.protein to colors.proteinContainer
        MealType.Snack -> colors.fat to colors.fatContainer
        MealType.Dinner -> colors.water to colors.waterContainer
    }
}

@DrawableRes
fun mealTypeIcon(type: MealType): Int = when (type) {
    MealType.Breakfast -> R.drawable.ic_sunrise
    MealType.Lunch -> R.drawable.ic_sun
    MealType.Snack -> R.drawable.ic_cookie
    MealType.Dinner -> R.drawable.ic_moon
}
