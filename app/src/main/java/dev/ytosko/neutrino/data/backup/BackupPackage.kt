package dev.ytosko.neutrino.data.backup

import dev.ytosko.neutrino.data.glucose.GlucoseEntity
import dev.ytosko.neutrino.data.meal.FoodEntity
import dev.ytosko.neutrino.data.meal.MealEntity
import dev.ytosko.neutrino.data.meal.MealItemEntity
import dev.ytosko.neutrino.data.meal.WaterEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Everything a backup restores. API keys are plain here; the whole package is encrypted. */
@Serializable
data class BackupData(
    val settings: SettingsSnapshot,
    val meals: List<MealEntity> = emptyList(),
    val items: List<MealItemEntity> = emptyList(),
    val foods: List<FoodEntity> = emptyList(),
    val water: List<WaterEntity> = emptyList(),
    val glucose: List<GlucoseEntity> = emptyList(),
)

@Serializable
data class SettingsSnapshot(
    val activeProvider: String? = null,
    /** Provider id → model. */
    val models: Map<String, String> = emptyMap(),
    /** Provider id → API key. */
    val apiKeys: Map<String, String> = emptyMap(),
    val photoDetail: String? = null,
)

/**
 * The (unencrypted) inside of a backup: a zip with `data.json` and one JPEG thumbnail per meal
 * that has a photo. Zip keeps photos out of the JSON and compresses the rest.
 */
object BackupPackage {

    private const val DATA = "data.json"
    private const val PHOTOS = "photos/"
    /** Guards restore against oversized or malicious files. */
    private const val MAX_UNPACKED_BYTES = 200L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun pack(data: BackupData, photos: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(DATA))
            zip.write(json.encodeToString(BackupData.serializer(), data).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            photos.forEach { (mealId, jpeg) ->
                // Photos are already JPEG-compressed; storing them avoids wasted work.
                zip.putNextEntry(ZipEntry("$PHOTOS${mealId.safeName()}.jpg"))
                zip.write(jpeg)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Returns the data and meal id → thumbnail JPEG. */
    fun unpack(bytes: ByteArray): Pair<BackupData, Map<String, ByteArray>> {
        var data: BackupData? = null
        val photos = mutableMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = zip.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_UNPACKED_BYTES) throw CorruptBackupException("The backup is too large to restore.")
                    buffer.write(chunk, 0, read)
                }
                when {
                    entry.name == DATA -> data = json.decodeFromString(BackupData.serializer(), buffer.toString(Charsets.UTF_8))
                    entry.name.startsWith(PHOTOS) && entry.name.endsWith(".jpg") ->
                        photos[entry.name.removePrefix(PHOTOS).removeSuffix(".jpg")] = buffer.toByteArray()
                }
            }
        }
        return (data ?: throw CorruptBackupException("The backup has no data.")) to photos
    }

    /** Meal ids are UUIDs; anything else is dropped so entry names can't escape the folder. */
    private fun String.safeName(): String = filter { it.isLetterOrDigit() || it == '-' }
}
