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
}
