package dev.ytosko.neutrino.data.medicine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedexClientTest {

    private fun resource(name: String) = javaClass.getResource("/medex/$name")!!.readText()

    @Test
    fun `search results become name, strength and form, without ads`() {
        val results = MedexClient.parseSearch(resource("search_napa.html"))
        assertTrue(results.size >= 8)
        val tablet = results.first { it.name == "Napa" && it.strength == "500 mg" && it.form == "Tablet" }
        assertTrue(tablet.url.startsWith("https://medex.com.bd/brands/"))
        assertTrue("no sponsored entries", results.none { "Sponsored" in it.name })
        assertTrue(results.any { it.name == "Napa Extra" && it.strength == "500 mg+65 mg" })
    }

    @Test
    fun `generic name comes from the brand page`() {
        assertEquals("Naproxen Sodium", MedexClient.parseGeneric(resource("brand_generic.html")))
        assertNull(MedexClient.parseGeneric("<html></html>"))
    }
}
