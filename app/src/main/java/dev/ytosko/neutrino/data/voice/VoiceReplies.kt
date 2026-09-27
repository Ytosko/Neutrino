package dev.ytosko.neutrino.data.voice

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

/**
 * Says the assistant's reply with the phone's own text-to-speech (free, no key, offline once the
 * voice is downloaded). When the phone is silent, the media volume is 0, replies are off, or no
 * voice speaks that language, the reply shows briefly as text instead.
 */
class VoiceReplies(context: Context) {

    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun say(text: String, speak: Boolean) {
        if (text.isBlank()) return
        if (!speak || muted()) {
            show(text)
            return
        }
        val engine = tts
        if (engine == null) {
            pending = text
            tts = TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                pending?.let { waiting ->
                    pending = null
                    if (ready) speakNow(waiting) else show(waiting)
                }
            }
        } else if (ready) {
            speakNow(text)
        } else {
            show(text)
        }
    }

    private fun speakNow(text: String) {
        val engine = tts ?: return show(text)
        // Bangla script is read with a Bangla voice when the phone has one; otherwise shown.
        val locale = if (text.any { it in 'ঀ'..'৿' }) Locale.forLanguageTag("bn-BD") else Locale.ENGLISH
        val available = engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
        if (!available) return show(text)
        engine.language = locale
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "neutrino-reply")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun muted(): Boolean {
        val audio = app.getSystemService(AudioManager::class.java) ?: return false
        return audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    }

    private fun show(text: String) = Toast.makeText(app, text, Toast.LENGTH_LONG).show()
}
