package dev.ytosko.neutrino.domain.food

import dev.ytosko.neutrino.data.food.FoodCatalog
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.ScannedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoodSystemTest {

    /** The real bundled list, so data mistakes (bad units, missing foods) fail the build. */
    private val catalog = FoodCatalog { File("src/main/assets/foods.json").readText() }

    @Test
    fun `catalog loads and every food has sane values`() {
        assertTrue(catalog.foods.size > 150)
        catalog.foods.forEach { food ->
            assertTrue("${food.name} kcal", food.per100g.calories in 0.0..900.0)
            val suggested = food.suggestedPortion
            assertNotNull("${food.name} default portion must be usable", food.grams(suggested.quantity, suggested.unit))
            // Macros can't weigh more than the food itself.
            val macroGrams = food.per100g.proteinG + food.per100g.carbsG + food.per100g.fatG
            assertTrue("${food.name} macros $macroGrams g per 100 g", macroGrams <= 101)
        }
        assertEquals("ids are unique", catalog.foods.size, catalog.foods.map { it.id }.toSet().size)
    }

    @Test
    fun `bangla names and english names both find foods`() {
        assertEquals("White rice, cooked", catalog.search("bhaat").first().name)
        assertTrue(catalog.search("dim").any { it.name.startsWith("Egg") })
        assertTrue(catalog.search("biryani").any { it.name == "Chicken biryani" })
        assertTrue(catalog.search("aam").any { it.name == "Mango" })
        assertTrue(catalog.search("chicken curry").first().name.contains("Chicken curry"))
        assertTrue(catalog.search("xyzzy").isEmpty())
    }

    @Test
    fun `units convert to grams, liquids allow ml`() {
        val milk = catalog.search("milk whole").first()
        assertEquals(257.5, milk.grams(250.0, FoodUnit.Milliliter)!!, 0.01)
        assertTrue(FoodUnit.Liter in milk.availableUnits)
        val rice = catalog.search("white rice").first()
        assertEquals(250.0, rice.grams(1.0, FoodUnit.Plate)!!, 0.0)
        assertNull(rice.grams(1.0, FoodUnit.Milliliter))
        assertEquals(FoodUnit.Plate, rice.availableUnits.first())
        assertEquals(1500.0, rice.grams(1.5, FoodUnit.Kilogram)!!, 0.0)
    }

    @Test
    fun `unit names from AI replies are understood`() {
        assertEquals(FoodUnit.Piece, FoodUnit.fromKey("pieces"))
        assertEquals(FoodUnit.Gram, FoodUnit.fromKey("grams"))
        assertEquals(FoodUnit.Tablespoon, FoodUnit.fromKey("Tablespoon"))
        assertEquals(FoodUnit.Liter, FoodUnit.fromKey("L"))
        assertNull(FoodUnit.fromKey("smidgen"))
    }

    @Test
    fun `match scores prefer name prefix, then aliases, and need every word`() {
        assertTrue(FoodRanking.matchScore("rice", "Rice noodles") > FoodRanking.matchScore("rice", "Fried rice"))
        assertTrue(FoodRanking.matchScore("dim", "Egg, boiled", listOf("dim")) > 0)
        assertEquals(0, FoodRanking.matchScore("beef rice", "Beef curry"))
        assertTrue(FoodRanking.matchScore("rôti", "Roti / chapati") > 0)
    }

    @Test
    fun `habits rank foods eaten often, recently and at this meal higher`() {
        val now = 100L * 86_400_000
        val biryaniAtDinner = FoodUsage(useCount = 6, lastUsedEpochMs = now - 3_600_000, mealCounts = mapOf(MealType.Dinner to 5, MealType.Lunch to 1))
        val oatsAtBreakfast = FoodUsage(useCount = 6, lastUsedEpochMs = now - 3_600_000, mealCounts = mapOf(MealType.Breakfast to 6))
        val oldFavourite = FoodUsage(useCount = 6, lastUsedEpochMs = now - 60L * 86_400_000, mealCounts = mapOf(MealType.Dinner to 6))

        assertTrue(FoodRanking.usageScore(biryaniAtDinner, MealType.Dinner, now) > FoodRanking.usageScore(oatsAtBreakfast, MealType.Dinner, now))
        assertTrue(FoodRanking.usageScore(oatsAtBreakfast, MealType.Breakfast, now) > FoodRanking.usageScore(biryaniAtDinner, MealType.Breakfast, now))
        assertTrue(FoodRanking.usageScore(biryaniAtDinner, MealType.Dinner, now) > FoodRanking.usageScore(oldFavourite, MealType.Dinner, now))
        assertEquals(0.0, FoodRanking.usageScore(FoodUsage(), MealType.Lunch, now), 0.0)
    }

    @Test
    fun `scanned food the user already knows reuses its nutrition`() {
        val rice = catalog.search("white rice").first()
        val scanned = ScannedItem("White rice, cooked", 1.0, "plate", 300.0, Nutrition(999.0, 1.0, 1.0, 1.0))
        val resolved = ScanFoods.resolve(scanned, rice)
        assertEquals(rice.id, resolved.food.id)
        assertEquals(Portion(1.0, FoodUnit.Plate), resolved.portion)
    }

    @Test
    fun `known food with an unsupported unit falls back to grams`() {
        val rice = catalog.search("white rice").first()
        val resolved = ScanFoods.resolve(ScannedItem("White rice, cooked", 2.0, "piece", 180.0, Nutrition(230.0, 4.0, 50.0, 0.5)), rice)
        assertEquals(Portion(180.0, FoodUnit.Gram), resolved.portion)
    }

    @Test
    fun `new scanned food is learned per 100 g with its household unit`() {
        val item = ScannedItem("Beef tehari", 1.0, "plate", 300.0, Nutrition(600.0, 24.0, 72.0, 24.0))
        val resolved = ScanFoods.resolve(item, known = null)
        val food = resolved.food
        assertEquals(FoodSource.Scan, food.source)
        assertEquals(200.0, food.per100g.calories, 0.001)
        assertEquals(300.0, food.grams(1.0, FoodUnit.Plate)!!, 0.001)
        val back = food.nutrition(1.0, FoodUnit.Plate)!!
        assertEquals(item.nutrition.calories, back.calories, 1e-9)
        assertEquals(item.nutrition.proteinG, back.proteinG, 1e-9)
        assertEquals(item.nutrition.carbsG, back.carbsG, 1e-9)
        assertEquals(FoodCategory.RiceDish, food.category)
        assertEquals("scan-beef-tehari", food.id)
    }

    @Test
    fun `scanned item without weight keeps the model's numbers`() {
        val resolved = ScanFoods.resolve(ScannedItem("Mystery stew", 1.0, "bowl", 0.0, Nutrition(300.0, 10.0, 30.0, 12.0)), null)
        assertEquals(Nutrition(300.0, 10.0, 30.0, 12.0), resolved.food.nutrition(1.0, FoodUnit.Bowl))
        assertFalse(resolved.food.unitGrams.isEmpty())
    }
}
