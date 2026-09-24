package dev.ytosko.neutrino.data.backup

import dev.ytosko.neutrino.data.meal.FoodEntity
import dev.ytosko.neutrino.data.meal.MealEntity
import dev.ytosko.neutrino.data.meal.MealItemEntity
import dev.ytosko.neutrino.data.meal.WaterEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

class BackupTest {

    // Low iteration count keeps tests fast; the format stores the count, so real files use the default.
    private val password = "correct horse".toCharArray()
    private val key = BackupCrypto.newBackupKey()
    private val wrapped = BackupCrypto.wrapKey(key, password, iterations = 1_000)

    private fun header(meals: Int = 2) = BackupHeader(
        createdAtEpochMs = 1_758_800_000_000,
        appVersion = "test",
        meals = meals,
        foods = 1,
        wrappedKey = wrapped,
    )

    private val meal = MealEntity(
        id = "0b6c5f7e-1111-4c1e-9d1a-000000000001", name = "Rice and dal", calories = 495.0, proteinG = 15.8,
        carbsG = 92.0, fatG = 5.8, mealType = "Lunch", eatenAtEpochMs = 1_758_790_000_000, zoneId = "Asia/Dhaka",
        thumbnailPath = "/data/old/thumb.jpg", syncedToHealthConnect = true, provider = "gemini", model = "m",
        inputTokens = 900, outputTokens = 118, createdAtEpochMs = 1_758_790_000_000,
    )
    private val data = BackupData(
        settings = SettingsSnapshot(activeProvider = "gemini", models = mapOf("gemini" to "m"), apiKeys = mapOf("gemini" to "AIza-secret"), photoDetail = "low"),
        meals = listOf(meal),
        items = listOf(
            MealItemEntity(rowId = 7, mealId = meal.id, position = 0, foodId = "rice", name = "White rice", category = "grain",
                quantity = 1.0, unit = "plate", grams = 250.0, calories = 325.0, proteinG = 6.7, carbsG = 70.0, fatG = 0.7),
        ),
        foods = listOf(
            FoodEntity(id = "custom-1", name = "Chicken Shawarma Wrap", category = "fastfood", kcalPer100g = 220.0,
                proteinPer100g = 12.0, carbsPer100g = 22.0, fatPer100g = 9.0, units = "piece=250.0", density = null,
                aliases = "", source = "custom", useCount = 3, lastUsedEpochMs = 1, breakfastCount = 0, lunchCount = 1,
                snackCount = 2, dinnerCount = 0, lastQuantity = 1.0, lastUnit = "piece", createdAtEpochMs = 1),
        ),
        water = listOf(WaterEntity("w1", 250, 1_758_790_000_000, "Asia/Dhaka", true)),
    )

    @Test
    fun `a backup round-trips with the password`() {
        val photo = byteArrayOf(-1, -40, -1, 1, 2, 3)
        val file = BackupCrypto.seal(BackupPackage.pack(data, mapOf(meal.id to photo)), key, header())

        val (payload, recoveredKey) = BackupCrypto.open(file, password)
        val (restored, photos) = BackupPackage.unpack(payload)

        assertArrayEquals(key, recoveredKey)
        assertEquals(data, restored)
        assertArrayEquals(photo, photos.getValue(meal.id))
    }

    @Test
    fun `the header is readable without the password but the data isn't`() {
        val file = BackupCrypto.seal(BackupPackage.pack(data, emptyMap()), key, header())
        assertEquals(2, BackupCrypto.readHeader(file).meals)
        val text = String(file, Charsets.ISO_8859_1)
        assertFalse("API key must not appear in plain text", text.contains("AIza-secret"))
        assertFalse(text.contains("Shawarma"))
    }

    @Test
    fun `a wrong password is reported as such`() {
        val file = BackupCrypto.seal(BackupPackage.pack(data, emptyMap()), key, header())
        try {
            BackupCrypto.open(file, "wrong password".toCharArray())
            fail("expected WrongPasswordException")
        } catch (_: WrongPasswordException) {
        }
    }

    @Test
    fun `tampering with the header or body is detected`() {
        val file = BackupCrypto.seal(BackupPackage.pack(data, emptyMap()), key, header())
        val headerTampered = String(file, Charsets.ISO_8859_1).replaceFirst("\"meals\":2", "\"meals\":9").toByteArray(Charsets.ISO_8859_1)
        val bodyTampered = file.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        for (bad in listOf(headerTampered, bodyTampered)) {
            try {
                BackupCrypto.open(bad, key)
                fail("expected CorruptBackupException")
            } catch (_: CorruptBackupException) {
            }
        }
    }

    @Test
    fun `other files are rejected`() {
        for (bad in listOf(ByteArray(0), "PK\u0003\u0004 not a backup".toByteArray())) {
            try {
                BackupCrypto.readHeader(bad)
                fail("expected CorruptBackupException")
            } catch (_: CorruptBackupException) {
            }
        }
    }

    @Test
    fun `changing the password keeps the same backup key`() {
        val rewrapped = BackupCrypto.wrapKey(key, "new password!".toCharArray(), iterations = 1_000)
        assertArrayEquals(key, BackupCrypto.unwrapKey(rewrapped, "new password!".toCharArray()))
    }

    @Test
    fun `drive backups are due by frequency with some slack`() {
        val day = TimeUnit.DAYS.toMillis(1)
        val now = 100 * day
        assertTrue(BackupRepository.isDue(null, BackupFrequency.Monthly, now))
        assertTrue("a daily run 22 h later still counts", BackupRepository.isDue(now - 22 * day / 24, BackupFrequency.Daily, now))
        assertFalse(BackupRepository.isDue(now - 12 * day / 24, BackupFrequency.Daily, now))
        assertFalse(BackupRepository.isDue(now - 5 * day, BackupFrequency.Weekly, now))
        assertTrue(BackupRepository.isDue(now - 7 * day, BackupFrequency.Weekly, now))
        assertFalse(BackupRepository.isDue(now - 20 * day, BackupFrequency.Monthly, now))
    }
}
