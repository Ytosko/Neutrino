package dev.ytosko.neutrino.ui.review

import dev.ytosko.neutrino.data.meal.PhotoProcessor
import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.food.Food
import dev.ytosko.neutrino.domain.food.FoodCategory
import dev.ytosko.neutrino.domain.food.FoodUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReviewLogicTest {

    private val rice = Food(
        id = "rice", name = "White rice, cooked", category = FoodCategory.Grain,
        per100g = Nutrition(130.0, 2.7, 28.0, 0.3),
        unitGrams = mapOf(FoodUnit.Plate to 250.0, FoodUnit.Cup to 158.0),
    )
    private val dal = Food(
        id = "dal", name = "Masoor dal", category = FoodCategory.Legume,
        per100g = Nutrition(85.0, 4.5, 11.0, 2.5),
        unitGrams = mapOf(FoodUnit.Bowl to 200.0),
    )

    private fun item(food: Food, qty: String, unit: FoodUnit, key: Long = 0) = ReviewItem(key, food, qty, unit)

    @Test
    fun `item nutrition scales with amount and unit`() {
        val plate = item(rice, "1.5", FoodUnit.Plate)
        assertEquals(375.0, plate.grams!!, 0.001)
        assertEquals(487.5, plate.nutrition.calories, 0.001)
        assertEquals(item(rice, "375", FoodUnit.Gram).nutrition, plate.nutrition)
    }

    @Test
    fun `totals add up items and invalid amounts count as zero`() {
        val state = ReviewUiState(
            phase = ReviewPhase.Ready,
            name = "Lunch",
            items = listOf(item(rice, "1", FoodUnit.Plate, 1), item(dal, "1", FoodUnit.Bowl, 2)),
        )
        assertEquals(325.0 + 170.0, state.nutrition.calories, 0.001)
        assertTrue(state.canSave)

        val broken = state.copy(items = state.items + item(dal, "", FoodUnit.Bowl, 3))
        assertFalse("an empty amount blocks saving", broken.canSave)
    }

    @Test
    fun `units a food doesn't support are invalid`() {
        assertNull(item(rice, "1", FoodUnit.Glass).grams)
        assertNull(item(rice, "1", FoodUnit.Milliliter).grams)
    }

    @Test
    fun `automatic meal names read naturally`() {
        val chicken = rice.copy(id = "c", name = "Chicken curry (murgi)")
        val egg = rice.copy(id = "e", name = "Egg, boiled")
        assertEquals("", autoName(emptyList()))
        assertEquals("White rice", autoName(listOf(item(rice, "1", FoodUnit.Plate))))
        assertEquals("White rice and Masoor dal", autoName(listOf(item(rice, "1", FoodUnit.Plate), item(dal, "1", FoodUnit.Bowl))))
        assertEquals(
            "White rice, Masoor dal and 2 more",
            autoName(listOf(rice, dal, chicken, egg).mapIndexed { i, f -> item(f, "1", FoodUnit.Gram, i.toLong()) }),
        )
    }

    @Test
    fun `a meal needs a name and at most fifty foods`() {
        val items = listOf(item(rice, "1", FoodUnit.Plate))
        assertFalse(ReviewUiState(phase = ReviewPhase.Ready, name = " ", items = items).canSave)
        assertTrue(ReviewUiState(phase = ReviewPhase.Ready, name = "Rice", items = items).canSave)
        val full = (0 until MAX_ITEMS).map { item(rice, "1", FoodUnit.Plate, it.toLong()) }
        assertFalse(ReviewUiState(items = full).canAddItem)
        assertTrue(ReviewUiState(items = full.drop(1)).canAddItem)
    }

    @Test
    fun `drinks show household units in ml, foods in grams`() {
        val cola = rice.copy(id = "cola", name = "Cola", category = FoodCategory.Drink, unitGrams = mapOf(FoodUnit.Can to 343.2), density = 1.04)
        val can = cola.unitSize(FoodUnit.Can)!!
        assertTrue(can.inMl)
        assertEquals(330.0, can.amount, 0.001)
        assertFalse(rice.unitSize(FoodUnit.Plate)!!.inMl)
        assertNull("mass units have no size hint", cola.unitSize(FoodUnit.Gram))
    }

    @Test
    fun `unit changes round sensibly`() {
        assertEquals(250.0, roundForUnit(249.6, FoodUnit.Gram), 0.0)
        assertEquals(1.5, roundForUnit(1.46, FoodUnit.Plate), 0.0)
        assertEquals(0.25, roundForUnit(0.05, FoodUnit.Piece), 0.0)
        assertEquals(0.38, roundForUnit(0.375, FoodUnit.Kilogram), 0.0)
    }

    @Test
    fun `exif time uses the offset tag when present, else the given zone`() {
        assertEquals(Instant.parse("2026-09-25T14:42:10Z"), PhotoProcessor.parseTakenAt("2026:09:25 20:42:10", "+06:00"))
        assertEquals(
            Instant.parse("2026-09-25T14:42:10Z"),
            PhotoProcessor.parseTakenAt("2026:09:25 20:42:10", null, ZoneId.of("Asia/Dhaka")),
        )
        assertNull(PhotoProcessor.parseTakenAt("garbage", null))
        assertNull(PhotoProcessor.parseTakenAt(null, null))
    }
}
