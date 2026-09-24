package dev.ytosko.neutrino.ui.food

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.domain.food.FoodCategory
import dev.ytosko.neutrino.domain.food.FoodUnit
import dev.ytosko.neutrino.domain.food.Portion
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme

@DrawableRes
fun FoodCategory.icon(): Int = when (this) {
    FoodCategory.Grain -> R.drawable.ic_wheat
    FoodCategory.RiceDish -> R.drawable.ic_cooking_pot
    FoodCategory.Bread -> R.drawable.ic_croissant
    FoodCategory.Poultry -> R.drawable.ic_drumstick
    FoodCategory.Meat -> R.drawable.ic_beef
    FoodCategory.Fish -> R.drawable.ic_fish
    FoodCategory.Egg -> R.drawable.ic_egg
    FoodCategory.Dairy -> R.drawable.ic_milk
    FoodCategory.Legume -> R.drawable.ic_bean
    FoodCategory.Vegetable -> R.drawable.ic_carrot
    FoodCategory.Leafy -> R.drawable.ic_leafy_green
    FoodCategory.Fruit -> R.drawable.ic_apple
    FoodCategory.Citrus -> R.drawable.ic_citrus
    FoodCategory.Grape -> R.drawable.ic_grape
    FoodCategory.Nuts -> R.drawable.ic_nut
    FoodCategory.Fat -> R.drawable.ic_droplet
    FoodCategory.Sweet -> R.drawable.ic_candy
    FoodCategory.Dessert -> R.drawable.ic_cake_slice
    FoodCategory.Snack -> R.drawable.ic_cookie
    FoodCategory.Curry -> R.drawable.ic_soup
    FoodCategory.FastFood -> R.drawable.ic_hamburger
    FoodCategory.Pizza -> R.drawable.ic_pizza
    FoodCategory.Drink -> R.drawable.ic_cup_soda
    FoodCategory.HotDrink -> R.drawable.ic_coffee
    FoodCategory.Packaged -> R.drawable.ic_package
    FoodCategory.Other -> R.drawable.ic_utensils
}

/** Category icon on a soft tile, tinted by food group so lists are easy to scan. */
@Composable
fun FoodIcon(category: FoodCategory, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val colors = NeutrinoTheme.colors
    val (container, content) = when (category) {
        FoodCategory.Grain, FoodCategory.RiceDish, FoodCategory.Bread, FoodCategory.Sweet,
        FoodCategory.Dessert, FoodCategory.Snack -> colors.carbsContainer to colors.carbs
        FoodCategory.Poultry, FoodCategory.Meat, FoodCategory.Fish, FoodCategory.Egg,
        FoodCategory.Legume, FoodCategory.Dairy -> colors.proteinContainer to colors.protein
        FoodCategory.Fat, FoodCategory.Nuts -> colors.fatContainer to colors.fat
        FoodCategory.Drink, FoodCategory.HotDrink -> colors.waterContainer to colors.water
        else -> null to null
    }
    if (container != null && content != null) {
        IconBadge(category.icon(), modifier = modifier, container = container, content = content, size = size)
    } else {
        IconBadge(category.icon(), modifier = modifier, size = size)
    }
}

@Composable
fun unitLabel(unit: FoodUnit): String = stringResource(
    when (unit) {
        FoodUnit.Gram -> R.string.unit_g
        FoodUnit.Kilogram -> R.string.unit_kg
        FoodUnit.Milliliter -> R.string.unit_ml
        FoodUnit.Liter -> R.string.unit_l
        FoodUnit.Plate -> R.string.unit_plate
        FoodUnit.Bowl -> R.string.unit_bowl
        FoodUnit.Cup -> R.string.unit_cup
        FoodUnit.Glass -> R.string.unit_glass
        FoodUnit.Piece -> R.string.unit_piece
        FoodUnit.Slice -> R.string.unit_slice
        FoodUnit.Tablespoon -> R.string.unit_tbsp
        FoodUnit.Teaspoon -> R.string.unit_tsp
        FoodUnit.Handful -> R.string.unit_handful
        FoodUnit.Scoop -> R.string.unit_scoop
        FoodUnit.Serving -> R.string.unit_serving
        FoodUnit.Can -> R.string.unit_can
    },
)

/** "1.5 plate", "250 g". */
@Composable
fun portionLabel(portion: Portion): String = "${formatQuantity(portion.quantity)} ${unitLabel(portion.unit)}"

fun formatQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
