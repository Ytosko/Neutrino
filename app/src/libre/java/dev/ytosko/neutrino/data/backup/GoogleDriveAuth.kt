package dev.ytosko.neutrino.data.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The libre (F-Droid) build has no Google Play services, so there is no Google Drive backup:
 * [isAvailable] is always false and the app offers only the encrypted backup file. Same shape as
 * the full build's class, so the rest of the app doesn't need to know which build it is.
 */
@Suppress("UNUSED_PARAMETER", "unused")
class GoogleDriveAuth(private val context: Context) {

    sealed interface Outcome {
        data class Token(val accessToken: String) : Outcome
        data class NeedsConsent(val intent: PendingIntent) : Outcome
    }

    val isAvailable: Boolean get() = false

    suspend fun authorize(email: String? = null, chooseAccount: Boolean = false): Outcome =
        throw DriveException(0, "Google Drive isn't part of this build.")

    fun tokenFromIntent(data: Intent?): String? = null

    suspend fun clearToken(token: String) = Unit

    suspend fun revoke(email: String) = Unit

    companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    }
}
