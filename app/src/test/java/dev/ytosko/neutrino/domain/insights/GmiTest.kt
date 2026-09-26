package dev.ytosko.neutrino.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class GmiTest {

    private val start = LocalDate.of(2026, 9, 1)

    private fun days(n: Int, mmol: Double) = (0 until n).map { start.plusDays(it.toLong()) to mmol }

    @Test
    fun `needs readings on at least 14 days`() {
        assertNull(GmiCalculator.from(days(13, 7.0)))
        // Many readings on few days still isn't enough.
        assertNull(GmiCalculator.from(days(13, 7.0) + days(13, 7.0)))
    }

    @Test
    fun `matches the published formula`() {
        // Mean 154 mg/dL (8.55 mmol/L) gives a GMI of about 7.0 %, 53 mmol/mol.
        val gmi = GmiCalculator.from(days(14, 154 / 18.018))!!
        assertEquals(6.99, gmi.percent, 0.01)
        assertEquals(53, gmi.mmolPerMol)
        assertEquals(14, gmi.readings)
        assertEquals(14, gmi.days)
    }

    @Test
    fun `averages every reading`() {
        val gmi = GmiCalculator.from(days(14, 5.0) + days(14, 9.0))!!
        assertEquals(3.31 + 0.02392 * 7.0 * 18.018, gmi.percent, 1e-9)
        assertEquals(28, gmi.readings)
    }
}
