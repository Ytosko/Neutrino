package dev.ytosko.neutrino.data.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Records speech into memory as a 16 kHz mono WAV, the one format every provider accepts. Nothing
 * is written to storage. Stops on [stop], after [SILENCE_MS] of quiet once the user has spoken (when
 * [stopOnSilence]), or at [MAX_MS].
 */
class VoiceRecorder {

    private val _level = MutableStateFlow(0f)
    /** 0..1 loudness of the last few milliseconds, for the pulsing mic. */
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var stopRequested = false

    /** Off while the user holds the mic: then only letting go stops it. */
    @Volatile var silenceStops = true

    fun stop() {
        stopRequested = true
    }

    /**
     * Records until stopped; returns the WAV, or null if the microphone couldn't be opened or nothing
     * louder than background noise was heard. Needs RECORD_AUDIO, checked by the caller.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(stopOnSilence: Boolean): ByteArray? = withContext(Dispatchers.IO) {
        stopRequested = false
        silenceStops = stopOnSilence
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return@withContext null
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4)
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return@withContext null

        val pcm = ByteArrayOutputStream(RATE * 2 * 10)
        val chunk = ShortArray(RATE / 20) // 50 ms
        var spoke = false
        var quietMs = 0
        var totalMs = 0
        var noise = 300.0
        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive && !stopRequested && totalMs < MAX_MS) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) break
                val bytes = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                for (i in 0 until read) {
                    bytes.putShort(chunk[i])
                    sum += chunk[i].toDouble() * chunk[i]
                }
                pcm.write(bytes.array())
                val rms = sqrt(sum / read)
                val ms = read * 1000 / RATE
                totalMs += ms
                // Speech is well above the room's noise, which is tracked while it's quiet.
                val loud = rms > maxOf(noise * 3, 700.0)
                if (!loud) noise = noise * 0.95 + rms * 0.05
                _level.value = (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()
                if (loud) {
                    spoke = true
                    quietMs = 0
                } else if (spoke) {
                    quietMs += ms
                    if (silenceStops && quietMs >= SILENCE_MS) break
                }
                // Nobody said anything at all: give up rather than record the room for a minute.
                if (!spoke && totalMs >= NOTHING_SAID_MS) break
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            _level.value = 0f
        }
        if (!spoke) null else wav(pcm.toByteArray())
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        return header.array() + pcm
    }

    companion object {
        const val RATE = 16_000
        const val MAX_MS = 60_000
        const val SILENCE_MS = 1_800
        const val NOTHING_SAID_MS = 8_000
    }
}
