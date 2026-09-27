package dev.ytosko.neutrino.ui.voice

import dev.ytosko.neutrino.domain.voice.ItemChange
import dev.ytosko.neutrino.data.voice.VoiceMeal
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import dev.ytosko.neutrino.AppContainer
import android.content.Context
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.glucose.GlucoseRelation
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.glucose.MealLinkChoice
import dev.ytosko.neutrino.data.meal.MealRepository
import java.time.Instant
import dev.ytosko.neutrino.data.medicine.MedicineEntity
import dev.ytosko.neutrino.data.medicine.MedicineRepository
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.data.voice.VoiceAssistant
import dev.ytosko.neutrino.data.voice.VoiceContext
import dev.ytosko.neutrino.data.voice.VoiceNotSetUp
import dev.ytosko.neutrino.data.voice.VoiceRecorder
import dev.ytosko.neutrino.data.voice.VoiceReplies
import dev.ytosko.neutrino.domain.voice.KnownMedicine
import dev.ytosko.neutrino.domain.voice.MedicineMatcher
import dev.ytosko.neutrino.domain.voice.SpokenGlucose
import dev.ytosko.neutrino.domain.voice.SpokenMedicine
import dev.ytosko.neutrino.domain.voice.VoiceCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

sealed interface VoicePhase {
    data object Idle : VoicePhase
    data object Listening : VoicePhase
    data object Thinking : VoicePhase
    /** Nothing was heard, or nothing that could be acted on. */
    data object Missed : VoicePhase
    data class Failed(val error: Exception) : VoicePhase
}

/** Several of the user's medicines fit what was said ("Metformin 500" and two brands of it). */
data class MedicineChoice(val spoken: SpokenMedicine, val options: List<MedicineEntity>)

/**
 * One conversation turn: listen, write down what was said, work out what to do, do it, and answer
 * out loud. Water and medicine are logged straight away; glucose waits for the user to confirm; the
 * meal part goes to [onMeal] (a new review page, or changes to the one on screen).
 */
class VoiceController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val assistant: VoiceAssistant,
    private val replies: VoiceReplies,
    private val settings: SettingsRepository,
    private val meals: MealRepository,
    private val medicines: MedicineRepository,
    private val glucose: GlucoseRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** The meal on screen, for the review page; null when logging from scratch. */
    private val contextFor: () -> VoiceContext = { VoiceContext(LocalDateTime.now(zone)) },
    /** Applies the meal part; returns the names of lines it couldn't change. */
    private val onMeal: suspend (VoiceCommand) -> List<String>,
) {
    private val recorder = VoiceRecorder()
    val level: StateFlow<Float> = recorder.level

    private val _phase = MutableStateFlow<VoicePhase>(VoicePhase.Idle)
    val phase: StateFlow<VoicePhase> = _phase.asStateFlow()

    private val _glucose = MutableStateFlow<SpokenGlucose?>(null)
    /** A reading waiting for Confirm; nothing is saved before. */
    val pendingGlucose: StateFlow<SpokenGlucose?> = _glucose.asStateFlow()

    private val _medicineChoices = MutableStateFlow<List<MedicineChoice>>(emptyList())
    private val _choice = MutableStateFlow<MedicineChoice?>(null)
    /** The medicine to pick when several fit; one at a time. */
    val medicineChoice: StateFlow<MedicineChoice?> = _choice.asStateFlow()

    /** The last turn's command, once everything it asked for is done (or was confirmed/dismissed). */
    private val _finished = MutableStateFlow<VoiceCommand?>(null)
    val finished: StateFlow<VoiceCommand?> = _finished.asStateFlow()

    private var job: Job? = null
    private var lastCommand: VoiceCommand? = null
    /** The medicine list as numbered for the AI this turn; null when the medicine log is off. */
    private var shownMedicines: List<MedicineEntity>? = null
    /** What the user said and the question asked about it, while waiting for the answer. */
    private var asked: Pair<String, String>? = null

    /** Starts listening; with [stopOnSilence] it also stops by itself when the user goes quiet. */
    fun start(stopOnSilence: Boolean = true) {
        if (_phase.value == VoicePhase.Listening || _phase.value == VoicePhase.Thinking) return
        replies.stop()
        _finished.value = null
        job = scope.launch {
            _phase.value = VoicePhase.Listening
            val wav = recorder.record(stopOnSilence)
            if (wav == null) {
                missed()
                return@launch
            }
            _phase.value = VoicePhase.Thinking
            try {
                val text = assistant.transcribe(wav)
                if (text.isBlank()) return@launch missed()
                val list = if (settings.settings.first().medicinesOn) medicines.activeMedicines() else null
                shownMedicines = list
                val context = contextFor().copy(medicines = list?.map(::medicineLine), asked = asked)
                val command = assistant.understand(text, context) ?: return@launch missed()
                if (command.question.isNotBlank()) {
                    ask(text, command.question)
                    return@launch
                }
                asked = null
                act(command)
            } catch (e: AiException) {
                _phase.value = VoicePhase.Failed(e)
            } catch (e: VoiceNotSetUp) {
                _phase.value = VoicePhase.Failed(e)
            }
        }
    }

    /** The user is holding the mic: keep listening through pauses until they let go. */
    fun holding() {
        recorder.silenceStops = false
    }

    /** Stops listening and goes on with what was said so far. */
    fun stop() = recorder.stop()

    /** Stops and throws away the current turn. */
    fun cancel() {
        asked = null
        recorder.stop()
        job?.cancel()
        replies.stop()
        _phase.value = VoicePhase.Idle
    }

    fun reset() {
        if (_phase.value is VoicePhase.Missed || _phase.value is VoicePhase.Failed) _phase.value = VoicePhase.Idle
    }

    /** Saves the reading as the user confirmed (or corrected) it in the pop-up. */
    fun saveGlucose(mmolPerL: Double, relation: GlucoseRelation, at: Instant, link: MealLinkChoice) {
        scope.launch {
            val added = glucose.addManual(mmolPerL, at, relation, zone)
            if (link != MealLinkChoice.Auto) glucose.setMealLink(added.id, link)
        }
        dismissGlucose()
    }

    fun dismissGlucose() {
        _glucose.value = null
        checkDone()
    }

    fun chooseMedicine(medicine: MedicineEntity?) {
        val choice = _choice.value ?: return
        if (medicine != null) scope.launch { logDose(medicine, choice.spoken) }
        _medicineChoices.value = _medicineChoices.value.drop(1)
        _choice.value = _medicineChoices.value.firstOrNull()
        checkDone()
    }

    /** Asks back ("About how much did they weigh?") and listens for the answer once it's been said. */
    private suspend fun ask(said: String, question: String) {
        asked = said to question
        _phase.value = VoicePhase.Idle
        replies.say(question, settings.settings.first().speakReplies) {
            if (_phase.value == VoicePhase.Idle) start(stopOnSilence = true)
        }
    }

    private suspend fun act(command: VoiceCommand) {
        val speak = settings.settings.first().speakReplies
        val notes = mutableListOf<String>()
        val now = LocalDateTime.now(zone)

        if (command.waterMl > 0) {
            val at = (if (contextFor().editing) null else command.mealTime) ?: now
            meals.addWater(command.waterMl, zone, at.atZone(zone).toInstant())
        }

        if (command.medicines.isNotEmpty()) {
            val list = shownMedicines
            when {
                list == null -> notes += context.getString(R.string.voice_medicine_off)
                list.isEmpty() -> notes += context.getString(R.string.voice_medicine_empty)
                else -> {
                    val known = list.map { KnownMedicine(it.id, it.name, it.generic, it.strength) }
                    val choices = mutableListOf<MedicineChoice>()
                    command.medicines.forEach { spoken ->
                        // The AI saw the list and names the line; if it couldn't tell, match here.
                        val picked = list.getOrNull(spoken.listNumber - 1)
                        val matches = if (picked != null) {
                            listOf(picked)
                        } else {
                            MedicineMatcher.match(spoken, known).mapNotNull { m -> list.firstOrNull { it.id == m.id } }
                        }
                        when (matches.size) {
                            0 -> notes += context.getString(R.string.voice_medicine_unknown, spoken.name)
                            1 -> logDose(matches.first(), spoken)
                            else -> choices += MedicineChoice(spoken, matches)
                        }
                    }
                    _medicineChoices.value = choices
                    _choice.value = choices.firstOrNull()
                }
            }
        }

        val unchanged = if (command.hasMeal || (command.mealTime != null && contextFor().editing)) onMeal(command) else emptyList()

        if (command.glucose != null) {
            _glucose.value = command.glucose
            notes += context.getString(R.string.voice_confirm_glucose)
        }

        lastCommand = command
        _phase.value = VoicePhase.Idle
        // The AI wrote its reply before the change was made; if a line couldn't be changed, say that instead.
        val reply = if (unchanged.isEmpty()) command.reply else context.getString(R.string.voice_change_failed, unchanged.joinToString(", "))
        replies.say((listOf(reply) + notes).filter { it.isNotBlank() }.joinToString(" "), speak)
        checkDone()
    }

    /** "Oramet SR 500 mg (Metformin Hydrochloride)", insulin marked as such. */
    private fun medicineLine(m: MedicineEntity): String = buildString {
        append(m.displayName)
        m.generic?.takeIf { it.isNotBlank() && !it.equals(m.name, ignoreCase = true) }?.let { append(" (").append(it).append(')') }
        if (m.kindEnum == dev.ytosko.neutrino.data.medicine.MedicineKind.Insulin) append(", insulin")
    }

    private suspend fun logDose(medicine: MedicineEntity, spoken: SpokenMedicine) {
        val amount = spoken.amount.takeIf { it > 0 } ?: medicine.usualDose ?: 1.0
        val at = (spoken.time ?: LocalDateTime.now(zone)).atZone(zone).toInstant()
        medicines.logDose(medicine, amount, at, zone)
    }

    private fun checkDone() {
        if (_glucose.value == null && _choice.value == null) lastCommand?.let { _finished.value = it }
    }

    private fun missed() {
        _phase.value = VoicePhase.Missed
        scope.launch { replies.say(context.getString(R.string.voice_missed), settings.settings.first().speakReplies) }
    }

    fun consumeFinished() {
        _finished.value = null
        lastCommand = null
    }
}

/** A voice controller wired to the app's repositories. */
fun AppContainer.voiceController(
    scope: CoroutineScope,
    context: Context,
    contextFor: () -> VoiceContext = { VoiceContext(LocalDateTime.now()) },
    onMeal: suspend (VoiceCommand) -> List<String>,
) = VoiceController(
    scope = scope,
    context = context.applicationContext,
    assistant = voice,
    replies = voiceReplies,
    settings = settings,
    meals = meals,
    medicines = medicines,
    glucose = glucose,
    contextFor = contextFor,
    onMeal = onMeal,
)

/** Log by voice from Home: a meal said goes to a new review page; everything else is logged here. */
class VoiceLogViewModel(container: AppContainer, context: Context) : ViewModel() {
    val controller = container.voiceController(viewModelScope, context) { command ->
        container.voiceMeal = VoiceMeal(
            items = command.items.filterIsInstance<ItemChange.Add>().map { it.item },
            eatenAt = command.mealTime,
        )
        emptyList()
    }
}
