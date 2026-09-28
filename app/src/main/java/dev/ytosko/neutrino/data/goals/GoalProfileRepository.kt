package dev.ytosko.neutrino.data.goals

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.ytosko.neutrino.domain.goals.Conditions
import dev.ytosko.neutrino.domain.goals.HeightUnit
import dev.ytosko.neutrino.domain.goals.Physique
import dev.ytosko.neutrino.domain.goals.PlanUnit
import dev.ytosko.neutrino.domain.goals.Sex
import dev.ytosko.neutrino.domain.goals.WeightUnit
import dev.ytosko.neutrino.domain.goals.Workout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.goalStore: DataStore<Preferences> by preferencesDataStore(name = "goal_profile")

/** The weekly weigh-in reminder: on by default, Monday 09:00. [day] is 1 (Monday) to 7 (Sunday). */
@Serializable
data class WeighIn(val on: Boolean = true, val day: Int = 1, val minute: Int = 9 * 60)

@Serializable
private data class StoredPhysique(
    val birthEpochDay: Long? = null,
    val sex: Sex? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val targetKg: Double? = null,
    val planLength: Int? = null,
    val planUnit: PlanUnit = PlanUnit.Months,
    val heightUnit: HeightUnit = HeightUnit.Cm,
    val weightUnit: WeightUnit = WeightUnit.Kg,
)

/**
 * What Daily goals knows about the person: physique, regular workouts, conditions and the weekly
 * weigh-in. Kept on the phone only; sent to the user's AI provider only when they ask for a
 * suggestion.
 */
class GoalProfileRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val physique = stringPreferencesKey("physique")
        val workouts = stringPreferencesKey("workouts")
        val conditions = stringPreferencesKey("conditions")
        val weighIn = stringPreferencesKey("weigh_in")
    }

    val physique: Flow<Physique> = context.goalStore.data.map { p ->
        val s = p[Keys.physique]?.let { runCatching { json.decodeFromString<StoredPhysique>(it) }.getOrNull() } ?: StoredPhysique()
        Physique(s.birthEpochDay?.let(java.time.LocalDate::ofEpochDay), s.sex, s.heightCm, s.weightKg, s.targetKg, s.planLength, s.planUnit, s.heightUnit, s.weightUnit)
    }

    val workouts: Flow<List<Workout>> = context.goalStore.data.map { p ->
        p[Keys.workouts]?.let { runCatching { json.decodeFromString<List<Workout>>(it) }.getOrNull() }.orEmpty()
    }

    val conditions: Flow<Conditions> = context.goalStore.data.map { p ->
        p[Keys.conditions]?.let { runCatching { json.decodeFromString<Conditions>(it) }.getOrNull() } ?: Conditions()
    }

    val weighIn: Flow<WeighIn> = context.goalStore.data.map { p ->
        p[Keys.weighIn]?.let { runCatching { json.decodeFromString<WeighIn>(it) }.getOrNull() } ?: WeighIn()
    }

    suspend fun setPhysique(p: Physique) = context.goalStore.edit {
        it[Keys.physique] = json.encodeToString(
            StoredPhysique(p.birthDate?.toEpochDay(), p.sex, p.heightCm, p.weightKg, p.targetKg, p.planLength, p.planUnit, p.heightUnit, p.weightUnit),
        )
    }

    suspend fun setWorkouts(list: List<Workout>) = context.goalStore.edit { it[Keys.workouts] = json.encodeToString(list) }

    suspend fun setConditions(c: Conditions) = context.goalStore.edit { it[Keys.conditions] = json.encodeToString(c) }

    suspend fun setWeighIn(w: WeighIn) = context.goalStore.edit { it[Keys.weighIn] = json.encodeToString(w) }
}
