package dev.ytosko.neutrino.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.ai.AiChain
import dev.ytosko.neutrino.data.ai.AiClient
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.AnalysisPrompt
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.goals.GoalProfileRepository
import dev.ytosko.neutrino.data.goals.WeighIn
import dev.ytosko.neutrino.data.health.HealthConnectManager
import dev.ytosko.neutrino.data.medicine.MedicineEntity
import dev.ytosko.neutrino.data.medicine.MedicineKind
import dev.ytosko.neutrino.data.medicine.MedicineRepository
import dev.ytosko.neutrino.data.reminders.WeighInReminder
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.goals.Conditions
import dev.ytosko.neutrino.domain.goals.GoalAdvice
import dev.ytosko.neutrino.domain.goals.GoalFacts
import dev.ytosko.neutrino.domain.goals.GoalMath
import dev.ytosko.neutrino.domain.goals.Intakes
import dev.ytosko.neutrino.domain.goals.Physique
import dev.ytosko.neutrino.domain.goals.LiverLog
import dev.ytosko.neutrino.domain.goals.LiverTest
import dev.ytosko.neutrino.domain.goals.WeightEntry
import dev.ytosko.neutrino.domain.goals.Workout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** Where a goal suggestion stands. */
sealed interface Advice {
    data object Idle : Advice
    data object Loading : Advice
    data object NeedsAi : Advice
    data class Ready(val advice: GoalAdvice) : Advice
    data class Failed(val error: AiException) : Advice
}

/**
 * Daily goals' physique, workouts and conditions, and the AI suggestion ("Recalculate my intakes"),
 * which starts from the phone's own numbers ([GoalMath]) and is kept within safe limits.
 */
class GoalsViewModel(
    private val context: Context,
    private val profile: GoalProfileRepository,
    private val settings: SettingsRepository,
    private val medicines: MedicineRepository,
    private val glucose: GlucoseRepository,
    private val healthConnect: HealthConnectManager,
    private val clients: Map<AiProvider, AiClient>,
) : ViewModel() {

    val physique: StateFlow<Physique> = profile.physique.stateIn(viewModelScope, SharingStarted.Eagerly, Physique())
    val workouts: StateFlow<List<Workout>> = profile.workouts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val conditions: StateFlow<Conditions> = profile.conditions.stateIn(viewModelScope, SharingStarted.Eagerly, Conditions())
    val weighIn: StateFlow<WeighIn> = profile.weighIn.stateIn(viewModelScope, SharingStarted.Eagerly, WeighIn())
    val medicineList: StateFlow<List<MedicineEntity>> =
        medicines.medicines.map { list -> list.filterNot { it.archived } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _savedConditions = MutableStateFlow<Conditions?>(null)

    /** The saved conditions, once read: My conditions starts editing from these. */
    val savedConditions: StateFlow<Conditions?> = _savedConditions.asStateFlow()

    private val _meterAverage = MutableStateFlow<Double?>(null)

    /** The meter readings' average over the last 30 days (mmol/L); null without readings. */
    val meterAverage: StateFlow<Double?> = _meterAverage.asStateFlow()

    private val _advice = MutableStateFlow<Advice>(Advice.Idle)
    val advice: StateFlow<Advice> = _advice.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = profile.conditions.first()
            // Liver values saved before the log existed become its first result.
            val old = LiverLog.fromOld(saved, LocalDate.now())
            if (old != null && profile.liverTests.first().isEmpty()) {
                profile.saveLiverTest(old)
                val cleared = saved.copy(cap = null, kpa = null, steatosisGrade = null, fibrosisGrade = null, alt = null, ast = null, liverTestDay = null)
                profile.setConditions(cleared)
                _savedConditions.value = cleared
            } else {
                _savedConditions.value = saved
            }
            // A weight saved before the history existed becomes its first entry.
            val kg = profile.physique.first().weightKg
            if (kg != null && profile.weights.first().isEmpty()) {
                val now = Instant.now().toEpochMilli()
                profile.addWeight(WeightEntry("weight-$now", now, kg))
            }
        }
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val readings = runCatching { glucose.observeBetween(today.minusDays(29), today, zone).first() }.getOrDefault(emptyList())
            _meterAverage.value = readings.takeIf { it.isNotEmpty() }?.map { it.mmolPerL }?.average()
        }
    }

    fun setPhysique(p: Physique) = viewModelScope.launch { profile.setPhysique(p) }

    val weights: StateFlow<List<WeightEntry>> = profile.weights.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val liverTests: StateFlow<List<LiverTest>> = profile.liverTests.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Logs a liver result (or saves an edited one); logging turns fatty liver on. */
    fun saveLiverTest(t: LiverTest) = viewModelScope.launch {
        profile.saveLiverTest(t)
        val c = profile.conditions.first()
        if (!c.liver) setConditions(c.copy(liver = true)).join()
    }

    fun removeLiverTest(id: String) = viewModelScope.launch { profile.removeLiverTest(id) }

    fun newLiverTestId(): String = "liver-" + UUID.randomUUID().toString()

    /** A weigh-in: kept in the history (the newest is the current weight), sent to Health Connect if allowed. */
    fun setWeight(kg: Double, at: Instant = Instant.now()) = viewModelScope.launch {
        val entry = WeightEntry("weight-${at.toEpochMilli()}", at.toEpochMilli(), kg)
        profile.addWeight(entry)
        runCatching { healthConnect.writeWeight(entry.id, kg, at, ZoneId.systemDefault()) }
        runCatching { WeighInReminder.sync(context) }
    }

    /** Changes a weigh-in's weight or day (a new record replaces the old one, also in Health Connect). */
    fun editWeight(old: WeightEntry, kg: Double, at: Instant) = viewModelScope.launch {
        profile.removeWeight(old.id)
        runCatching { healthConnect.deleteWeight(old.id) }
        val entry = WeightEntry("weight-${at.toEpochMilli()}", at.toEpochMilli(), kg)
        profile.addWeight(entry)
        runCatching { healthConnect.writeWeight(entry.id, kg, at, ZoneId.systemDefault()) }
    }

    fun removeWeight(id: String) = viewModelScope.launch {
        profile.removeWeight(id)
        runCatching { healthConnect.deleteWeight(id) }
        runCatching { WeighInReminder.sync(context) }
    }

    fun setWeighIn(w: WeighIn) = viewModelScope.launch {
        profile.setWeighIn(w)
        runCatching { WeighInReminder.sync(context) }
    }

    fun setWorkouts(list: List<Workout>) = viewModelScope.launch { profile.setWorkouts(list) }

    fun saveWorkout(w: Workout) = setWorkouts(workouts.value.filterNot { it.id == w.id } + w)

    fun removeWorkout(id: String) = setWorkouts(workouts.value.filterNot { it.id == id })

    fun newWorkoutId(): String = UUID.randomUUID().toString()

    fun setConditions(c: Conditions) = viewModelScope.launch {
        profile.setConditions(c)
        _savedConditions.value = c
    }

    /** Asks the AI for daily intakes from everything entered; physique must be complete. */
    fun analyse() {
        val p = physique.value
        if (!p.complete || _advice.value == Advice.Loading) return
        viewModelScope.launch {
            _advice.value = Advice.Loading
            val chain = settings.aiChain()
            if (chain.isEmpty()) {
                _advice.value = Advice.NeedsAi
                return@launch
            }
            val facts = GoalFacts.build(
                p, workouts.value, conditions.value, meterAverage.value, medicineList.value.map(::describe),
                weights = weights.value,
                liverTests = liverTests.value,
            )
            val bangla = Locale.getDefault().language == "bn"
            try {
                val (_, advice) = AiChain.run(chain, clients) { client, key, config ->
                    val reply = client.generateJson(key, config.model, AnalysisPrompt.goalPlan(facts, bangla), AnalysisPrompt.GOAL_SCHEMA)
                    AnalysisPrompt.parseGoalPlan(reply.text) ?: throw AiException.NoResult()
                } ?: run {
                    _advice.value = Advice.NeedsAi
                    return@launch
                }
                val safe = GoalMath.safe(advice.intakes, p.sex, conditions.value.diabetes)
                _advice.value = Advice.Ready(advice.copy(intakes = safe))
            } catch (e: AiException) {
                _advice.value = Advice.Failed(e)
            }
        }
    }

    fun clearAdvice() {
        _advice.value = Advice.Idle
    }

    /** Saves the confirmed intakes as the daily goals. */
    fun confirm(i: Intakes) = viewModelScope.launch {
        settings.setGoals(i.carbsG, i.proteinG, i.fatG, i.kcal, i.waterMl)
        _advice.value = Advice.Idle
    }

    /** "Metformin (Metformin) 500 mg, 1 tablet at 08:00, 20:00" or "Insulin: NovoRapid, rapid-acting…". */
    private fun describe(m: MedicineEntity): String {
        val name = listOfNotNull(
            if (m.kindEnum == MedicineKind.Insulin) "Insulin" else null,
            m.displayName,
            m.generic?.takeIf { it.isNotBlank() && !it.equals(m.name, ignoreCase = true) }?.let { "($it)" },
            m.insulinTypeEnum?.name?.lowercase()?.let { "$it-acting" },
        ).joinToString(" ")
        val dose = m.usualDose?.let { d -> "${if (d % 1.0 == 0.0) d.toInt() else d} ${m.unitEnum.name.lowercase()}" }
        val times = m.reminders.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "at $it" }
        return listOfNotNull(name, dose, times).joinToString(", ")
    }
}
