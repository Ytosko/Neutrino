package dev.ytosko.neutrino.data.backup

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gets short-lived Drive access tokens through Google Play services. Only the `drive.appdata`
 * scope is ever requested: Neutrino can see its own hidden backup folder and nothing else in
 * the user's Drive. No client secret or server is involved; Google matches the app by its
 * package name and signing certificate.
 */
class GoogleDriveAuth(private val context: Context) {

    sealed interface Outcome {
        data class Token(val accessToken: String) : Outcome
        /** The user has to pick an account or approve access; launch this and pass the result to [tokenFromIntent]. */
        data class NeedsConsent(val intent: PendingIntent) : Outcome
    }

    private val client get() = Identity.getAuthorizationClient(context)

    val isAvailable: Boolean
        get() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** Asks for a token, for [email]'s account when known. Throws if Play services fails. */
    suspend fun authorize(email: String? = null): Outcome {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA)))
            .apply { if (email != null) setAccount(Account(email, ACCOUNT_TYPE)) }
            .build()
        val result = client.authorize(request).await()
        val pending = result.pendingIntent
        return when {
            result.hasResolution() && pending != null -> Outcome.NeedsConsent(pending)
            result.accessToken != null -> Outcome.Token(result.accessToken!!)
            else -> throw DriveException(0, "Google didn't grant Drive access.")
        }
    }

    /** Reads the token from the consent screen's result, or null if the user cancelled. */
    fun tokenFromIntent(data: Intent?): String? =
        runCatching { client.getAuthorizationResultFromIntent(data).accessToken }.getOrNull()

    /** Drops a cached token that Drive rejected, so the next [authorize] fetches a fresh one. */
    suspend fun clearToken(token: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    /** Removes Neutrino's Drive access from the Google account. */
    suspend fun revoke(email: String) {
        runCatching {
            client.revokeAccess(
                RevokeAccessRequest.builder()
                    .setAccount(Account(email, ACCOUNT_TYPE))
                    .setScopes(listOf(Scope(DRIVE_APPDATA)))
                    .build(),
            ).await()
        }
    }

    companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        private const val ACCOUNT_TYPE = "com.google"
    }
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
