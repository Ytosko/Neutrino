package dev.ytosko.neutrino.data.meal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

/** A meal photo ready to send: small JPEG with no metadata, plus when it was taken (if known). */
class PreparedPhoto(val jpeg: ByteArray, val takenAt: Instant?)

/**
 * Prepares photos for analysis. Re-encoding drops all EXIF (including GPS), and downscaling
 * to [maxEdgePx] keeps image tokens (the main cost of each request) low.
 */
class PhotoProcessor(private val context: Context) {

    suspend fun prepare(uri: Uri, maxEdgePx: Int): PreparedPhoto = withContext(Dispatchers.IO) {
        val (orientation, takenAt) = readExif(uri)
        val bitmap = decodeScaled(uri, maxEdgePx) ?: error("Could not decode image")
        val upright = rotate(bitmap, orientation)
        PreparedPhoto(jpeg = upright.toJpeg(quality = 80), takenAt = takenAt)
    }

    /**
     * Saves a meal's photos (up to [MAX_PHOTOS]) as small JPEGs: the first as the thumbnail the meal
     * list shows (its path is returned), the others beside it. Old extra photos are removed.
     */
    suspend fun savePhotos(jpegs: List<ByteArray>, id: String): String? {
        deleteExtras(id)
        jpegs.drop(1).take(MAX_PHOTOS - 1).forEachIndexed { i, jpeg -> saveThumbnail(jpeg, id, index = i + 1) }
        return jpegs.firstOrNull()?.let { saveThumbnail(it, id) }
    }

    /** SHA-256 of the photo file as picked, so the same photo isn't added to a meal twice. Null if unreadable. */
    suspend fun fingerprint(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: return@runCatching null
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /** All photos of a meal, the thumbnail first; empty if it has none. */
    suspend fun readPhotos(id: String, thumbnailPath: String?): List<ByteArray> = withContext(Dispatchers.IO) {
        val first = thumbnailPath?.let { runCatching { File(it).readBytes() }.getOrNull() } ?: return@withContext emptyList()
        listOf(first) + extraFiles(id).mapNotNull { runCatching { it.readBytes() }.getOrNull() }
    }

    /** The extra photos' files (2nd to 5th), in order. */
    fun extraFiles(id: String): List<File> = (2..MAX_PHOTOS).map { File(dir, "$id-$it.jpg") }.filter { it.isFile }

    /** Deletes all of a meal's photos. */
    fun deletePhotos(id: String, thumbnailPath: String?) {
        deleteThumbnail(thumbnailPath)
        deleteExtras(id)
    }

    private fun deleteExtras(id: String) = extraFiles(id).forEach { runCatching { it.delete() } }

    private val dir: File get() = File(context.filesDir, "thumbnails")

    /** Saves a small photo for the meal ([index] 0 is the thumbnail, 1.. the extra ones); returns its path. */
    suspend fun saveThumbnail(jpeg: ByteArray, id: String, index: Int = 0): String? = withContext(Dispatchers.IO) {
        runCatching {
            val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@runCatching null
            val scale = THUMB_EDGE_PX.toFloat() / max(source.width, source.height)
            val thumb = if (scale < 1f) {
                Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt(), (source.height * scale).roundToInt(), true)
            } else {
                source
            }
            dir.mkdirs()
            val name = if (index == 0) "$id.jpg" else "$id-${index + 1}.jpg"
            File(dir, name).apply { writeBytes(thumb.toJpeg(quality = 80)) }.absolutePath
        }.getOrNull()
    }

    fun deleteThumbnail(path: String?) {
        path?.let { runCatching { File(it).delete() } }
    }

    private fun readExif(uri: Uri): Pair<Int, Instant?> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            orientation to parseTakenAt(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            )
        }
    }.getOrNull() ?: (ExifInterface.ORIENTATION_NORMAL to null)

    private fun decodeScaled(uri: Uri, maxEdgePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdgePx) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val scale = maxEdgePx.toFloat() / max(decoded.width, decoded.height)
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
        } else {
            decoded
        }
    }

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    companion object {
        /** Big enough to look sharp in the photo gallery, small enough for backups. */
        private const val THUMB_EDGE_PX = 480
        /** Most photos one meal can have. */
        const val MAX_PHOTOS = 5
        private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

        /** EXIF stores local wall time; the offset tag (if present) pins it to an instant. */
        internal fun parseTakenAt(dateTime: String?, offset: String?, fallbackZone: ZoneId = ZoneId.systemDefault()): Instant? =
            runCatching {
                val local = LocalDateTime.parse(dateTime?.trim(), EXIF_DATE)
                if (!offset.isNullOrBlank()) OffsetDateTime.of(local, java.time.ZoneOffset.of(offset.trim())).toInstant()
                else local.atZone(fallbackZone).toInstant()
            }.getOrNull()
    }
}
