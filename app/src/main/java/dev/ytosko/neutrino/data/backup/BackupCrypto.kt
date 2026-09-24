package dev.ytosko.neutrino.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Neutrino backup file format (`.nbk`):
 *
 * ```
 * "NTRBK" 0x01 | header length (int) | header JSON | IV (12) | AES-256-GCM ciphertext
 * ```
 *
 * The payload is encrypted with a random **backup key** that never changes for an install. The
 * header carries that key wrapped (AES-GCM) with a key derived from the user's backup password
 * (PBKDF2-HMAC-SHA256), plus a little plain metadata so a restore screen can show what it found
 * before asking for the password. The header is the GCM associated data, so it can't be altered
 * without breaking decryption.
 *
 * Because the backup key is kept on the phone (Keystore-protected), scheduled backups run without
 * asking for the password; the password is only needed to restore.
 */
object BackupCrypto {

    private val MAGIC = byteArrayOf('N'.code.toByte(), 'T'.code.toByte(), 'R'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), 1)
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_HEADER_BYTES = 16 * 1024
    const val MIN_PASSWORD_LENGTH = 8

    /** Tuned for ~0.5–1 s on a mid-range phone; only paid when setting a password or restoring. */
    const val DEFAULT_ITERATIONS = 310_000

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random = SecureRandom()

    fun newBackupKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    /** Locks [backupKey] with [password]. Store the result; it goes into every backup header. */
    fun wrapKey(backupKey: ByteArray, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): WrappedKey {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, derive(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        }
        return WrappedKey(salt.b64(), iterations, (iv + cipher.doFinal(backupKey)).b64())
    }

    /** Returns the backup key, or throws [WrongPasswordException]. */
    fun unwrapKey(wrapped: WrappedKey, password: CharArray): ByteArray {
        val sealed = wrapped.key.unb64()
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, derive(password, wrapped.salt.unb64(), wrapped.iterations), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            }.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (_: GeneralSecurityException) {
            throw WrongPasswordException()
        }
    }

    fun seal(payload: ByteArray, backupKey: ByteArray, header: BackupHeader): ByteArray {
        val headerBytes = json.encodeToString(BackupHeader.serializer(), header).toByteArray(Charsets.UTF_8)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(backupKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(headerBytes)
        }
        val body = cipher.doFinal(payload)
        return ByteArrayOutputStream(MAGIC.size + 4 + headerBytes.size + IV_BYTES + body.size).also { out ->
            DataOutputStream(out).apply {
                write(MAGIC)
                writeInt(headerBytes.size)
                write(headerBytes)
                write(iv)
                write(body)
            }
        }.toByteArray()
    }

    /** Reads the plain header without a password, e.g. to show "Backup from 25 Sep, 214 meals". */
    fun readHeader(file: ByteArray): BackupHeader = parse(file).header

    /** Decrypts with the already-unwrapped [backupKey]. */
    fun open(file: ByteArray, backupKey: ByteArray): ByteArray {
        val parsed = parse(file)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(backupKey, "AES"), GCMParameterSpec(TAG_BITS, parsed.iv))
                updateAAD(parsed.headerBytes)
            }.doFinal(parsed.body)
        } catch (_: GeneralSecurityException) {
            throw CorruptBackupException("The backup is damaged or was changed.")
        }
    }

    /** Unwraps the key with [password] and decrypts. Returns the payload and the backup key. */
    fun open(file: ByteArray, password: CharArray): Pair<ByteArray, ByteArray> {
        val key = unwrapKey(readHeader(file).wrappedKey, password)
        return open(file, key) to key
    }

    private class Parsed(val header: BackupHeader, val headerBytes: ByteArray, val iv: ByteArray, val body: ByteArray)

    private fun parse(file: ByteArray): Parsed {
        if (file.size < MAGIC.size + 4 || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw CorruptBackupException("This isn't a Neutrino backup file.")
        }
        val input = DataInputStream(file.inputStream(MAGIC.size, file.size - MAGIC.size))
        val headerSize = input.readInt()
        val bodyStart = MAGIC.size + 4 + headerSize + IV_BYTES
        if (headerSize !in 1..MAX_HEADER_BYTES || bodyStart > file.size) throw CorruptBackupException("The backup file is incomplete.")
        val headerBytes = ByteArray(headerSize).also(input::readFully)
        val iv = ByteArray(IV_BYTES).also(input::readFully)
        val header = runCatching { json.decodeFromString(BackupHeader.serializer(), headerBytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw CorruptBackupException("The backup file is damaged.") }
        if (header.format > BackupHeader.FORMAT) throw CorruptBackupException("This backup was made by a newer Neutrino. Update the app first.")
        return Parsed(header, headerBytes, iv, file.copyOfRange(bodyStart, file.size))
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
    private fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)
}

/** The backup key, locked with the user's password. Safe to store and to put in backups. */
@Serializable
data class WrappedKey(val salt: String, val iterations: Int, val key: String)

/** Unencrypted part of a backup file. Holds nothing personal beyond dates and counts. */
@Serializable
data class BackupHeader(
    val format: Int = FORMAT,
    val createdAtEpochMs: Long,
    val appVersion: String,
    val meals: Int,
    val foods: Int,
    val wrappedKey: WrappedKey,
) {
    companion object {
        const val FORMAT = 1
    }
}

class WrongPasswordException : Exception("Wrong backup password")

class CorruptBackupException(message: String) : Exception(message)
