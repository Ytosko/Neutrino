package dev.ytosko.neutrino.domain.food

import dev.ytosko.neutrino.data.food.FoodCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SouthAsianCatalogTest {

    /** The real bundled list (tools/fooddb builds it). */
    private val catalog = FoodCatalog { File("src/main/assets/foods.json").readText() }

    private fun Food.mentions(vararg words: String): Boolean {
        val text = (listOf(name) + aliases).joinToString(" ").normalizedForSearch()
        return words.any { it.normalizedForSearch() in text }
    }

    private fun assertFirst(query: String, vararg expected: String) {
        val first = catalog.search(query).firstOrNull()
        assertTrue("'$query' found ${first?.name}", first != null && first.mentions(*expected))
    }

    @Test
    fun `catalog covers South Asian foods`() {
        assertTrue(catalog.foods.size >= 600)
    }

    @Test
    fun `everyday Bangladeshi foods come first in search`() {
        assertFirst("porota", "porota", "paratha")
        assertFirst("পরোটা", "পরোটা")
        assertFirst("biryani", "biryani")
        assertFirst("ilish", "ilish", "hilsa")
        assertFirst("roshogolla", "roshogolla", "rasgulla")
        assertFirst("ইলিশ", "ইলিশ")
        assertFirst("ভাত", "ভাত")
        assertFirst("fuchka", "fuchka")
        assertFirst("dosa", "dosa")
        assertEquals("Paratha", catalog.search("পরোটা").first().name)
    }

    @Test
    fun `Bangla script survives search normalisation`() {
        assertEquals("পরোটা".normalizedForSearch(), "  পরোটা! ".normalizedForSearch())
        assertTrue("পরোটা".normalizedForSearch().isNotEmpty())
        // Precomposed য় and য + nukta are the same letter.
        assertEquals("য়".normalizedForSearch(), "য়".normalizedForSearch())
        assertEquals(85, FoodRanking.matchScore("পরোটা", "Paratha", listOf("porota", "পরোটা")))
        // A Bangla query must not match everything (it used to normalise to an empty string).
        assertEquals(0, FoodRanking.matchScore("ভাত", "Paratha", listOf("porota", "পরোটা")))
        // Latin accents are still ignored.
        assertTrue(FoodRanking.matchScore("rôti", "Roti / chapati") > 0)
    }
}
