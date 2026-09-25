package dev.ytosko.neutrino.ui.components

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

/** One macro total: value above label, coloured value plus a text label (never colour alone). */
@Composable
fun MacroStat(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .heightIn(min = 64.dp)
            .padding(vertical = Spacing.sm, horizontal = Spacing.xs)
            .clearAndSetSemantics { contentDescription = "$label $value" },
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
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Carbs, protein, fat and kcal in four tiles. Numbers are shortened ("1.2kg", "12k") so even
 * long ranges fit.
 */
@Composable
fun TotalsCard(totals: Nutrition?, modifier: Modifier = Modifier) {
    val colors = NeutrinoTheme.colors
    val n = totals ?: Nutrition.ZERO
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            MacroStat(compactGrams(n.carbsG), stringResource(R.string.macro_carbs), colors.carbs, Modifier.weight(1f))
            MacroStat(compactGrams(n.proteinG), stringResource(R.string.macro_protein), colors.protein, Modifier.weight(1f))
            MacroStat(compactGrams(n.fatG), stringResource(R.string.macro_fat), colors.fat, Modifier.weight(1f))
            MacroStat(compactNumber(n.calories), stringResource(R.string.macro_energy), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
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
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(icon = icon)
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
