package dev.ytosko.neutrino.ui.review

import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.ScannedItem
import dev.ytosko.neutrino.domain.food.Food
import dev.ytosko.neutrino.domain.food.FoodCategory
import dev.ytosko.neutrino.domain.food.FoodUnit
import dev.ytosko.neutrino.domain.food.Portion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceAmountTest {

    /** The built-in "Chicken burger / sandwich": 250 kcal per 100 g, 187 g a piece. */
    private val burger = Food(
        id = "usda-170295",
        name = "Chicken burger / sandwich",
        category = FoodCategory.FastFood,
        per100g = Nutrition(250.0, 16.28, 20.89, 11.19),
        unitGrams = mapOf(FoodUnit.Piece to 187.0),
    )

    private fun said(quantity: Double, unit: String, grams: Double, kcal: Double = 0.0) =
        ScannedItem("Chicken burger", quantity, unit, grams, Nutrition(kcal, 0.0, 0.0, 0.0))

    @Test
    fun `three burgers weighing 100 g in all become 100 g`() {
        assertEquals(Portion(100.0, FoodUnit.Gram), voiceAmount(burger, said(3.0, "piece", 100.0, 250.0)))
    }

    @Test
    fun `a count that matches the weight keeps the pieces`() {
        assertEquals(Portion(2.0, FoodUnit.Piece), voiceAmount(burger, said(2.0, "piece", 374.0)))
        assertEquals(Portion(4.0, FoodUnit.Piece), voiceAmount(burger, said(4.0, "pieces", 0.0)))
    }

    @Test
    fun `a unit the food doesn't have goes by weight`() {
        assertEquals(Portion(210.0, FoodUnit.Gram), voiceAmount(burger, said(300.0, "ml", 210.0)))
    }

    @Test
    fun `calories alone give the weight`() {
        assertEquals(Portion(120.0, FoodUnit.Gram), voiceAmount(burger, said(1.0, "serving", 0.0, 300.0)))
    }

    @Test
    fun `nothing to go on changes nothing`() {
        assertNull(voiceAmount(burger, said(1.0, "serving", 0.0)))
    }
}
