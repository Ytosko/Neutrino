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

    /** Saves a small thumbnail for the meal list; returns its absolute path. */
    suspend fun saveThumbnail(jpeg: ByteArray, id: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@runCatching null
            val scale = THUMB_EDGE_PX.toFloat() / max(source.width, source.height)
            val thumb = if (scale < 1f) {
                Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt(), (source.height * scale).roundToInt(), true)
            } else {
                source
            }
            val dir = File(context.filesDir, "thumbnails").apply { mkdirs() }
            File(dir, "$id.jpg").apply { writeBytes(thumb.toJpeg(quality = 75)) }.absolutePath
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
        private const val THUMB_EDGE_PX = 256
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
