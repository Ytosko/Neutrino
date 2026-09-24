package dev.ytosko.neutrino.data.backup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.ytosko.neutrino.BuildConfig
import dev.ytosko.neutrino.data.meal.MealDatabase
import dev.ytosko.neutrino.data.security.SecretCipher
import dev.ytosko.neutrino.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

private val Context.backupStore: DataStore<Preferences> by preferencesDataStore(name = "backup")

enum class BackupFrequency(val id: String, val days: Int) {
    Daily("daily", 1), Weekly("weekly", 7), Monthly("monthly", 30);

    companion object {
        fun fromId(id: String?): BackupFrequency = entries.firstOrNull { it.id == id } ?: Daily
    }
}

enum class DriveProblem { SignInNeeded, Failed }

data class BackupState(
    val passwordSet: Boolean = false,
    /** Neutrino may write its backup folder ("All files access" or the storage permission). */
    val storageAccess: Boolean = false,
    val lastLocalAt: Long? = null,
    /** The last write to the backup folder failed, e.g. storage access was turned off. */
    val localFailed: Boolean = false,
    /** Signed-in Google account; non-null means Drive backup is on. */
    val driveEmail: String? = null,
    val driveFrequency: BackupFrequency = BackupFrequency.Daily,
    val lastDriveAt: Long? = null,
    val driveProblem: DriveProblem? = null,
    val lastSizeBytes: Long? = null,
) {
    val configured: Boolean get() = passwordSet && storageAccess
    val driveConnected: Boolean get() = driveEmail != null
    val needsAttention: Boolean get() = !configured || localFailed || driveProblem != null
}

/** Where a restore came from, so later backups keep going to the same place. */
sealed interface RestoreSource {
    data object Phone : RestoreSource
    data class Drive(val email: String?) : RestoreSource
}

/**
 * WhatsApp-style backups: an encrypted file in a fixed, hidden folder on the phone (required; see
 * [LocalBackupFile]), plus an optional copy in the hidden Neutrino folder of the user's Google Drive. Everything the
 * app knows is included: AI settings and keys, meals with their photos, water and the personal
 * food directory. See [BackupCrypto] for the file format.
 */
class BackupRepository(
    private val context: Context,
    private val db: MealDatabase,
    private val settings: SettingsRepository,
    private val cipher: SecretCipher,
    private val drive: DriveClient,
    val auth: GoogleDriveAuth,
    val local: LocalBackupFile,
    private val json: Json,
) {
    private val store = context.applicationContext.backupStore
    private val mutex = Mutex()
    private val thumbnails get() = File(context.filesDir, "thumbnails")

    private object Keys {
        val wrappedKey = stringPreferencesKey("wrapped_key")
        val backupKey = stringPreferencesKey("backup_key")
        val lastLocalAt = longPreferencesKey("last_local_at")
        val localFailed = booleanPreferencesKey("local_failed")
        val driveEmail = stringPreferencesKey("drive_email")
        val driveFileId = stringPreferencesKey("drive_file_id")
        val driveFrequency = stringPreferencesKey("drive_frequency")
        val lastDriveAt = longPreferencesKey("last_drive_at")
        val driveProblem = stringPreferencesKey("drive_problem")
        val lastSize = longPreferencesKey("last_size")
    }

    private val preferences: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private val access = MutableStateFlow(local.hasAccess())

    /** Re-reads storage access, e.g. after returning from the system permission screen. */
    fun refreshAccess() {
        access.value = local.hasAccess()
    }

    val state: Flow<BackupState> = combine(preferences, access) { p, hasAccess ->
        BackupState(
            passwordSet = p[Keys.wrappedKey] != null && p[Keys.backupKey] != null,
            storageAccess = hasAccess,
            lastLocalAt = p[Keys.lastLocalAt],
            localFailed = p[Keys.localFailed] ?: false,
            driveEmail = p[Keys.driveEmail],
            driveFrequency = BackupFrequency.fromId(p[Keys.driveFrequency]),
            lastDriveAt = p[Keys.lastDriveAt],
            driveProblem = p[Keys.driveProblem]?.let { name -> DriveProblem.entries.firstOrNull { it.name == name } },
            lastSizeBytes = p[Keys.lastSize],
        )
    }

    // ---- Setup -------------------------------------------------------------------------------

    /** Sets (or changes) the backup password. The backup key stays the same, so nothing is re-encrypted. */
    suspend fun setPassword(password: CharArray) {
        val key = backupKey() ?: BackupCrypto.newBackupKey()
        val wrapped = withContext(Dispatchers.Default) { BackupCrypto.wrapKey(key, password) }
        saveKey(key, wrapped)
    }

    suspend fun setFrequency(frequency: BackupFrequency) {
        store.edit { it[Keys.driveFrequency] = frequency.id }
    }

    /** Links Drive with a token from the consent flow and uploads straight away. */
    suspend fun connectDrive(token: String) {
        val email = driveEmail(token)
        store.edit {
            if (email != null) it[Keys.driveEmail] = email else it[Keys.driveEmail] = ""
            it.remove(Keys.driveFileId)
            it.remove(Keys.driveProblem)
        }
        mutex.withLock { uploadToDrive(buildFile(), token) }
    }

    suspend fun disconnectDrive() {
        val email = preferences.first()[Keys.driveEmail]
        if (!email.isNullOrEmpty()) auth.revoke(email)
        store.edit {
            it.remove(Keys.driveEmail)
            it.remove(Keys.driveFileId)
            it.remove(Keys.lastDriveAt)
            it.remove(Keys.driveProblem)
        }
    }

    // ---- Backing up --------------------------------------------------------------------------

    /**
     * Writes the local backup and, when Drive is on and a copy is due (or [forceDrive]), uploads
     * one. [driveToken] skips asking Play services for a token. Returns what happened.
     */
    suspend fun backUp(forceDrive: Boolean = false, driveToken: String? = null): BackupRun = mutex.withLock {
        val p = preferences.first()
        if (p[Keys.wrappedKey] == null) return BackupRun(local = false, drive = null)
        val file = buildFile()
        val localOk = writeLocal(file)
        val driveOn = p[Keys.driveEmail] != null
        val due = forceDrive || isDue(p[Keys.lastDriveAt], BackupFrequency.fromId(p[Keys.driveFrequency]))
        val driveResult = if (driveOn && due) uploadToDrive(file, driveToken) else null
        BackupRun(localOk, driveResult)
    }

    data class BackupRun(val local: Boolean, val drive: Boolean?)

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(BackupWorker.PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Backs up a little after data changes; repeated changes push it back so saves are batched. */
    fun scheduleSoon() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>().setInitialDelay(10, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork(BackupWorker.SOON, ExistingWorkPolicy.REPLACE, request)
    }

    private suspend fun buildFile(): ByteArray = withContext(Dispatchers.IO) {
        val p = preferences.first()
        val wrapped = json.decodeFromString(WrappedKey.serializer(), requireNotNull(p[Keys.wrappedKey]))
        val key = requireNotNull(backupKey()) { "Backup key unavailable" }
        val dao = db.backup()
        val meals = dao.meals()
        val data = BackupData(settings.snapshot(), meals, dao.items(), dao.foods(), dao.water())
        val photos = meals.mapNotNull { meal ->
            meal.thumbnailPath?.let(::File)?.takeIf { it.isFile }?.let { meal.id to it.readBytes() }
        }.toMap()
        val header = BackupHeader(
            createdAtEpochMs = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            meals = meals.size,
            foods = data.foods.size,
            wrappedKey = wrapped,
        )
        BackupCrypto.seal(BackupPackage.pack(data, photos), key, header)
    }

    private suspend fun writeLocal(file: ByteArray): Boolean {
        refreshAccess()
        val ok = local.hasAccess() && local.write(file)
        store.edit {
            it[Keys.localFailed] = !ok
            if (ok) {
                it[Keys.lastLocalAt] = System.currentTimeMillis()
                it[Keys.lastSize] = file.size.toLong()
            }
        }
        return ok
    }

    /** Returns true on success; records a problem otherwise. */
    private suspend fun uploadToDrive(file: ByteArray, knownToken: String?): Boolean {
        val email = preferences.first()[Keys.driveEmail]?.takeIf { it.isNotEmpty() }
        suspend fun freshToken(): String? = when (val outcome = runCatching { auth.authorize(email) }.getOrNull()) {
            is GoogleDriveAuth.Outcome.Token -> outcome.accessToken
            else -> null
        }
        var token = knownToken ?: freshToken() ?: return recordDrive(DriveProblem.SignInNeeded)
        return try {
            val result = try {
                upload(token, file)
            } catch (_: DriveAuthException) {
                auth.clearToken(token)
                token = freshToken() ?: return recordDrive(DriveProblem.SignInNeeded)
                upload(token, file)
            }
            store.edit {
                it[Keys.driveFileId] = result.id
                it[Keys.lastDriveAt] = System.currentTimeMillis()
                it[Keys.lastSize] = file.size.toLong()
                it.remove(Keys.driveProblem)
            }
            true
        } catch (_: DriveAuthException) {
            recordDrive(DriveProblem.SignInNeeded)
        } catch (_: DriveException) {
            recordDrive(DriveProblem.Failed)
        }
    }

    private suspend fun upload(token: String, file: ByteArray): DriveClient.DriveFile {
        val knownId = preferences.first()[Keys.driveFileId] ?: drive.findBackup(token)?.id
        return try {
            drive.upload(token, file, knownId)
        } catch (e: DriveException) {
            // The file may have been removed (e.g. the user cleared app data in Drive); start a new one.
            if (knownId != null && e.code == 404) drive.upload(token, file, null) else throw e
        }
    }

    private suspend fun recordDrive(problem: DriveProblem): Boolean {
        store.edit { it[Keys.driveProblem] = problem.name }
        return false
    }

    // ---- Restoring ---------------------------------------------------------------------------

    /** Reads a backup's plain header, e.g. to show its date before asking for the password. */
    fun inspect(file: ByteArray): BackupHeader = BackupCrypto.readHeader(file)

    /** Finds and downloads the Drive backup, or returns null if there is none. */
    suspend fun downloadFromDrive(token: String): ByteArray? {
        val found = drive.findBackup(token) ?: return null
        return drive.download(token, found.id)
    }

    /** The Google account's email, or null if Drive won't say. */
    suspend fun driveEmail(token: String): String? = runCatching { drive.accountEmail(token) }.getOrNull()

    /** The backup in the phone's backup folder, or null if there is none (or no access yet). */
    suspend fun readPhoneBackup(): ByteArray? = local.read()

    /**
     * Replaces all app data with the backup's. Throws [WrongPasswordException] or
     * [CorruptBackupException]. Future backups keep the same key and password.
     */
    suspend fun restore(file: ByteArray, password: CharArray, source: RestoreSource) = mutex.withLock {
        val header = BackupCrypto.readHeader(file)
        val (payload, key) = withContext(Dispatchers.Default) { BackupCrypto.open(file, password) }
        val (data, photos) = withContext(Dispatchers.Default) { BackupPackage.unpack(payload) }

        withContext(Dispatchers.IO) {
            thumbnails.listFiles()?.forEach { it.delete() }
            thumbnails.mkdirs()
            val mealIds = data.meals.mapTo(HashSet()) { it.id }
            val saved = photos.filterKeys { it in mealIds }.mapValues { (id, jpeg) ->
                File(thumbnails, "$id.jpg").apply { writeBytes(jpeg) }.absolutePath
            }
            val meals = data.meals.map { it.copy(thumbnailPath = saved[it.id]) }
            db.backup().replaceAll(meals, data.items, data.foods, data.water)
        }
        settings.restore(data.settings)
        saveKey(key, header.wrappedKey)

        when (source) {
            RestoreSource.Phone -> Unit
            is RestoreSource.Drive -> store.edit {
                it[Keys.driveEmail] = source.email.orEmpty()
                it[Keys.lastDriveAt] = header.createdAtEpochMs
                it.remove(Keys.driveProblem)
            }
        }
        schedule()
    }

    // ---- Keys --------------------------------------------------------------------------------

    private suspend fun backupKey(): ByteArray? {
        val encrypted = preferences.first()[Keys.backupKey] ?: return null
        return withContext(Dispatchers.IO) { cipher.decrypt(encrypted) }?.let { Base64.getDecoder().decode(it) }
    }

    private suspend fun saveKey(key: ByteArray, wrapped: WrappedKey) {
        val encrypted = withContext(Dispatchers.IO) { cipher.encrypt(Base64.getEncoder().encodeToString(key)) }
        store.edit {
            it[Keys.backupKey] = encrypted
            it[Keys.wrappedKey] = json.encodeToString(WrappedKey.serializer(), wrapped)
        }
    }

    companion object {
        /** Slack so a daily backup that ran a bit late yesterday still runs today. */
        private val SLACK_MS = TimeUnit.HOURS.toMillis(3)

        fun isDue(lastAt: Long?, frequency: BackupFrequency, now: Long = System.currentTimeMillis()): Boolean =
            lastAt == null || now - lastAt >= TimeUnit.DAYS.toMillis(frequency.days.toLong()) - SLACK_MS
    }
}
