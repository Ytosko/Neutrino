package dev.ytosko.neutrino.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Minimal Google Drive v3 client for the hidden app data folder (`drive.appdata` scope). The
 * folder is private to Neutrino: the user can't browse it and nobody else, including the
 * developer, can read it. Only one backup file is kept, replaced on each upload.
 */
class DriveClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://www.googleapis.com",
) {

    @Serializable
    data class DriveFile(val id: String, val name: String = "", val size: Long? = null, val modifiedTime: String? = null)

    @Serializable
    private data class FileList(val files: List<DriveFile> = emptyList())

    @Serializable
    private data class About(val user: User? = null) {
        @Serializable
        data class User(val emailAddress: String? = null)
    }

    /** The newest Neutrino backup in the app folder, if any. */
    suspend fun findBackup(token: String): DriveFile? {
        val url = "$baseUrl/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '$FILE_NAME' and trashed = false")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("fields", "files(id,name,size,modifiedTime)")
            .build()
        val body = execute(Request.Builder().url(url).auth(token).build()) { it.body.string() }
        return json.decodeFromString(FileList.serializer(), body).files.firstOrNull()
    }

    suspend fun download(token: String, fileId: String): ByteArray {
        val url = "$baseUrl/drive/v3/files/$fileId".toHttpUrl().newBuilder().addQueryParameter("alt", "media").build()
        return execute(Request.Builder().url(url).auth(token).build()) { it.body.bytes() }
    }

    /** Uploads [bytes], replacing [existingId] when given. Uses a resumable session so size isn't limited to 5 MB. */
    suspend fun upload(token: String, bytes: ByteArray, existingId: String?): DriveFile {
        val metadata = buildJsonObject {
            put("name", FILE_NAME)
            put("mimeType", MIME)
            if (existingId == null) putJsonArray("parents") { add("appDataFolder") }
        }.toString()
        val sessionUrl = (if (existingId == null) "$baseUrl/upload/drive/v3/files" else "$baseUrl/upload/drive/v3/files/$existingId")
            .toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", "id,name,size,modifiedTime")
            .build()
        val start = Request.Builder()
            .url(sessionUrl)
            .auth(token)
            .header("X-Upload-Content-Type", MIME)
            .header("X-Upload-Content-Length", bytes.size.toString())
            .method(if (existingId == null) "POST" else "PATCH", metadata.toRequestBody(JSON_TYPE))
            .build()
        val location = execute(start) { it.header("Location") } ?: throw DriveException(0, "Drive didn't start the upload.")
        val put = Request.Builder().url(location).auth(token).put(bytes.toRequestBody(MIME.toMediaType())).build()
        val body = execute(put) { it.body.string() }
        return json.decodeFromString(DriveFile.serializer(), body)
    }

    /** The signed-in account's email, shown in Settings. */
    suspend fun accountEmail(token: String): String? {
        val url = "$baseUrl/drive/v3/about".toHttpUrl().newBuilder().addQueryParameter("fields", "user(emailAddress)").build()
        val body = execute(Request.Builder().url(url).auth(token).build()) { it.body.string() }
        return json.decodeFromString(About.serializer(), body).user?.emailAddress
    }

    private suspend fun <T> execute(request: Request, read: (Response) -> T): T = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> read(response)
                    response.code == 401 -> throw DriveAuthException()
                    else -> throw DriveException(response.code, "Google Drive error ${response.code}")
                }
            }
        } catch (e: IOException) {
            throw DriveException(0, "Couldn't reach Google Drive", e)
        }
    }

    private fun Request.Builder.auth(token: String) = header("Authorization", "Bearer $token")

    companion object {
        const val FILE_NAME = "neutrino-backup.nbk"
        private const val MIME = "application/octet-stream"
        private val JSON_TYPE = "application/json; charset=UTF-8".toMediaType()
    }
}

open class DriveException(val code: Int, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The access token expired or access was revoked; ask Google for a fresh one. */
class DriveAuthException : DriveException(401, "Google Drive access expired")
