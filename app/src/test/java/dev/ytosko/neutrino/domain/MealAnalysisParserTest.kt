package dev.ytosko.neutrino.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MealAnalysisParserTest {

    @Test
    fun `parses the exact schema`() {
        val result = MealAnalysisParser.parse(
            """{"calories": 540, "protein_g": 34, "carbs_g": 63.5, "fat_g": 18, "food_name": "Rice, chicken curry and dal"}""",
        )!!
        assertEquals("Rice, chicken curry and dal", result.foodName)
        assertEquals(Nutrition(540.0, 34.0, 63.5, 18.0), result.nutrition)
    }

    @Test
    fun `tolerates markdown fences, extra text and numbers as strings`() {
        val result = MealAnalysisParser.parse(
            "Sure! Here it is:\n```json\n{\"calories\": \"420 kcal\", \"protein_g\": \"12\", \"carbs_g\": 70, \"fat_g\": 9, \"food_name\": \"Biryani\"}\n```",
        )!!
        assertEquals(Nutrition(420.0, 12.0, 70.0, 9.0), result.nutrition)
        assertEquals("Biryani", result.foodName)
    }

    @Test
    fun `negative, missing and NaN values become zero and blank names get a default`() {
        val result = MealAnalysisParser.parse("""{"calories": -5, "carbs_g": "abc", "fat_g": 3, "food_name": "  "}""")!!
        assertEquals(Nutrition(0.0, 0.0, 0.0, 3.0), result.nutrition)
        assertEquals("Meal", result.foodName)
    }

    @Test
    fun `no json or no nutrition fields returns null`() {
        assertNull(MealAnalysisParser.parse("I can't see any food in this image."))
        assertNull(MealAnalysisParser.parse("""{"food_name": "Cat"}"""))
    }

    @Test
    fun `all-zero result is recognised as no food`() {
        val result = MealAnalysisParser.parse("""{"calories": 0, "protein_g": 0, "carbs_g": 0, "fat_g": 0, "food_name": "Empty plate"}""")!!
        assertTrue(result.nutrition.isEmpty)
    }

    @Test
    fun `item list is parsed and blank names are skipped`() {
        val result = MealAnalysisParser.parse(
            """{"food_name": "", "items": [
                {"name": "Chicken biryani", "quantity": 1, "unit": "plate", "grams": 350, "calories": 665, "protein_g": 30, "carbs_g": 84, "fat_g": 24},
                {"name": "", "quantity": 1, "unit": "piece", "grams": 50, "calories": 100, "protein_g": 1, "carbs_g": 1, "fat_g": 1},
                {"name": "Borhani", "quantity": "1", "unit": "", "grams": 200, "calories": 90, "protein_g": 4, "carbs_g": 12, "fat_g": 3}
            ]}""",
        )!!
        assertEquals(2, result.items.size)
        assertEquals("Chicken biryani, Borhani", result.foodName)
        assertEquals("serving", result.items[1].unit)
        assertEquals(755.0, result.nutrition.calories, 0.0)
    }

    @Test
    fun `empty item list means no food`() {
        val result = MealAnalysisParser.parse("""{"food_name": "Table", "items": []}""")!!
        assertTrue(result.items.isEmpty())
        assertTrue(result.nutrition.isEmpty)
    }

    @Test
    fun `food estimate rejects empty nutrition and odd densities`() {
        assertNull(MealAnalysisParser.parseFoodEstimate("""{"name":"Air","kcal_100g":0,"protein_100g":0,"carbs_100g":0,"fat_100g":0}"""))
        val estimate = MealAnalysisParser.parseFoodEstimate(
            """{"name":"Lassi","category":"drink","kcal_100g":90,"protein_100g":3,"carbs_100g":14,"fat_100g":2.5,"units":[{"unit":"Glass","grams":250},{"unit":"cup","grams":-5}],"g_per_ml":1.04}""",
        )!!
        assertEquals(mapOf("glass" to 250.0), estimate.unitGrams)
        assertEquals(1.04, estimate.gramsPerMl!!, 0.0)
    }
}
