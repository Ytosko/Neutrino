package dev.ytosko.neutrino.data.backup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The on-phone backup: always `/.Neutrino/data/backup/neutrino-backup.nbk` in shared storage, so
 * it survives uninstalling the app and a new install finds it without a file picker. The dot
 * hides the folder from normal file browsing. Writing needs "All files access" (Android 11+) or
 * the storage permission (Android 9–10).
 */
class LocalBackupFile(private val context: Context) {

    data class Info(val sizeBytes: Long, val modifiedAtEpochMs: Long)

    val folder: File get() = File(Environment.getExternalStorageDirectory(), "$ROOT/data/backup")
    private val file: File get() = File(folder, FILE_NAME)

    fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Android 11+: the system screen with the "Allow access to manage all files" switch. */
    fun accessSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    suspend fun info(): Info? = withContext(Dispatchers.IO) {
        runCatching { file.takeIf { it.isFile }?.let { Info(it.length(), it.lastModified()) } }.getOrNull()
    }

    suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull()
    }

    /** Replaces the backup. Writes a temp file first so a crash never leaves half a backup. */
    suspend fun write(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            folder.mkdirs()
            // Keeps media scanners and gallery apps out of the folder.
            File(folder.parentFile, ".nomedia").takeIf { !it.exists() }?.createNewFile()
            val temp = File(folder, "$FILE_NAME.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file)) { "Couldn't replace the backup" }
            }
        }.isSuccess
    }

    companion object {
        const val ROOT = ".Neutrino"
        const val FILE_NAME = "neutrino-backup.nbk"
    }
}
