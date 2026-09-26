package dev.ytosko.neutrino.wear.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WearProtocolTest {

    private val snapshot = WearSnapshot(
        date = "2026-09-26",
        carbs = WearMacro(amount = 142.4, text = "142g", goal = 150.0, goalText = "150g"),
        protein = WearMacro(amount = 61.0, text = "61g"),
        fat = WearMacro(amount = 48.2, text = "48.2g", goal = 60.0, goalText = "60g"),
        kcal = WearMacro(amount = 1_234.0, text = "1.2k", goal = 2_000.0, goalText = "2k"),
        waterMl = 1_250,
        waterText = "1.25 L",
        waterGoalMl = 2_000,
        canAddWater = true,
        glucose = WearGlucose(value = "6.4", unit = "mmol/L", mmolPerL = 6.4, measuredAtEpochMs = 1_790_000_000_000, band = GlucoseBand.InRange),
        publishedAtEpochMs = 1_790_000_100_000,
    )

    @Test
    fun snapshotRoundTrips() {
        assertEquals(snapshot, WearSnapshot.decode(snapshot.encode()))
    }

    @Test
    fun snapshotWithoutGoalsOrGlucoseRoundTrips() {
        val bare = snapshot.copy(
            carbs = WearMacro(0.0, "0g"),
            kcal = WearMacro(0.0, "0"),
            waterGoalMl = null,
            glucose = null,
        )
        val decoded = WearSnapshot.decode(bare.encode())
        assertEquals(bare, decoded)
        assertNull(decoded!!.carbs.progress)
    }

    @Test
    fun bandIsWrittenAsStableName() {
        val text = snapshot.encode()
        assertTrue(text, text.contains("\"band\":\"in_range\""))
        assertFalse(text.contains("\"glucose\":null"))
    }

    @Test
    fun decodeIgnoresUnknownFieldsFromNewerPhones() {
        val newer = snapshot.encode().replaceFirst("{", "{\"doses\":[1,2],")
        assertEquals(snapshot, WearSnapshot.decode(newer))
    }

    @Test
    fun decodeRejectsGarbage() {
        assertNull(WearSnapshot.decode(null))
        assertNull(WearSnapshot.decode(""))
        assertNull(WearSnapshot.decode("{not json"))
        assertNull(WearSnapshot.decode("{\"date\":\"2026-09-26\"}"))
    }

    @Test
    fun progressTowardGoal() {
        assertEquals(142.4f / 150f, snapshot.carbs.progress!!, 0.0001f)
        assertNull(snapshot.protein.progress)
        assertNull(WearMacro(10.0, "10g", goal = 0.0).progress)
    }

    @Test
    fun sameDayIsUnchanged() {
        assertSame(snapshot, snapshot.forDay("2026-09-26"))
    }

    @Test
    fun earlierDayStartsFromZeroButKeepsGoalsAndGlucose() {
        val next = snapshot.forDay("2026-09-27")
        assertEquals("2026-09-27", next.date)
        assertEquals(0.0, next.carbs.amount, 0.0)
        assertEquals("0g", next.carbs.text)
        assertEquals(150.0, next.carbs.goal!!, 0.0)
        assertEquals("0", next.kcal.text)
        assertEquals(0, next.waterMl)
        assertEquals("0 ml", next.waterText)
        assertEquals(snapshot.glucose, next.glucose)
    }

    @Test
    fun parsesWater() {
        assertEquals(WearAction.AddWater(250), WearAction.parse("water:250"))
        assertEquals(WearAction.AddWater(250), WearAction.parse(" water:250\n".toByteArray()))
    }

    @Test
    fun rejectsBadWaterAmounts() {
        for (bad in listOf("water:", "water", "water:0", "water:-250", "water:abc", "water:2001", "water:250:1")) {
            assertEquals(bad, WearAction.Unknown(bad), WearAction.parse(bad))
        }
    }

    @Test
    fun parsesRefresh() {
        assertEquals(WearAction.Refresh, WearAction.parse("refresh"))
        assertEquals(WearAction.Unknown("refresh:now"), WearAction.parse("refresh:now"))
    }

    @Test
    fun unknownKindsAreKeptForLaterVersions() {
        assertEquals(WearAction.Unknown("dose:abc-123"), WearAction.parse("dose:abc-123"))
        assertEquals(WearAction.Unknown(""), WearAction.parse(""))
    }

    @Test
    fun actionsEncodeToWhatTheyParseFrom() {
        for (action in listOf(WearAction.AddWater(250), WearAction.Refresh, WearAction.Unknown("dose:7"))) {
            assertEquals(action, WearAction.parse(action.encode()))
        }
        assertEquals("water:250", WearAction.AddWater(250).encode())
    }
}
