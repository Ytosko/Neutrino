package dev.ytosko.neutrino.domain.food

import dev.ytosko.neutrino.data.ai.AnalysisPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipesTest {

    private val reply = """{"name": "Chicken curry", "ingredients": [
        {"name": "Chicken", "grams": 1000, "calories": 1650, "protein_g": 190, "carbs_g": 0, "fat_g": 95},
        {"name": "Mustard oil", "grams": 56, "calories": 495, "protein_g": 0, "carbs_g": 0, "fat_g": 56},
        {"name": "Onion", "grams": 300, "calories": 120, "protein_g": 3, "carbs_g": 27, "fat_g": 0}],
        "cooked_grams": 1200}"""

    @Test
    fun `one plate is the cooked dish shared out`() {
        val recipe = RecipeParser.parse(reply, "Dish", servings = 6, unit = FoodUnit.Plate)!!
        assertEquals(3, recipe.ingredients.size)
        assertEquals(2265.0, recipe.total.calories, 0.01)
        assertEquals(200.0, recipe.gramsPerServing, 0.01)
        assertEquals(377.5, recipe.perServing.calories, 0.01)

        val food = recipe.toFood("recipe-1")
        assertEquals(FoodSource.Custom, food.source)
        assertEquals(200.0, food.grams(1.0, FoodUnit.Plate)!!, 0.01)
        // One plate of the saved food gives back one sixth of the pot.
        assertEquals(377.5, food.nutrition(1.0, FoodUnit.Plate)!!.calories, 0.01)
        assertEquals(Portion(1.0, FoodUnit.Plate), food.defaultPortion)
    }

    @Test
    fun `without a cooked weight the ingredients' weight is used`() {
        val recipe = RecipeParser.parse(reply.replace("\"cooked_grams\": 1200", "\"cooked_grams\": 0"), "Dish", 4, FoodUnit.Bowl)!!
        assertEquals(1356.0, recipe.totalGrams, 0.01)
        assertNull(RecipeParser.parse("""{"ingredients": []}""", "Dish", 4, FoodUnit.Bowl))
    }

    @Test
    fun `the recipe prompt lists the ingredients, cleanly`() {
        val prompt = AnalysisPrompt.recipe("Mum's curry", "1 kg chicken\n\n3 tbsp \"mustard\" oil")
        assertTrue("- 1 kg chicken" in prompt)
        assertTrue("- 3 tbsp  mustard  oil" in prompt)
        assertTrue(prompt.lines().none { it.startsWith("            ") })
    }

    private fun meal(id: String, at: Long, type: String, vararg foods: String) = UsualMeals.Logged(id, at, type, foods.toSet())

    @Test
    fun `usual meals are the same foods eaten twice or more, this meal type first`() {
        val meals = listOf(
            meal("a1", 1, "Breakfast", "roti", "egg", "tea"),
            meal("a2", 5, "Breakfast", "roti", "egg", "tea"),
            meal("b1", 2, "Lunch", "rice", "curry"),
            meal("b2", 3, "Lunch", "rice", "curry"),
            meal("b3", 4, "Lunch", "rice", "curry"),
            meal("c1", 6, "Dinner", "pizza"),
        )
        val atLunch = UsualMeals.pick(meals, "Lunch")
        assertEquals(listOf("b3", "a2"), atLunch.map { it.mealId })
        assertEquals(3, atLunch.first().times)
        assertEquals(listOf("a2", "b3"), UsualMeals.pick(meals, "Breakfast").map { it.mealId })
    }
}
