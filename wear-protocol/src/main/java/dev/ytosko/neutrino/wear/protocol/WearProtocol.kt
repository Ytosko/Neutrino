package dev.ytosko.neutrino.wear.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Data Layer paths and capabilities shared by the phone and the watch. */
object WearPaths {
    /** DataItem with today's [WearSnapshot], written by the phone. */
    const val TODAY = "/neutrino/today"

    /** Message from the watch to the phone; the payload is a [WearAction] in text. */
    const val ACTION = "/neutrino/action"

    /** Capability the phone app advertises, so the watch only talks to phones with Neutrino. */
    const val PHONE_CAPABILITY = "neutrino_phone"

    /** Key of the JSON snapshot inside the DataItem's DataMap. */
    const val SNAPSHOT_KEY = "snapshot"
}

/** Blood glucose compared with the user's target range. */
@Serializable
enum class GlucoseBand {
    @SerialName("low") Low,
    @SerialName("in_range") InRange,
    @SerialName("high") High,
}

/**
 * One macro for the day: the raw amount, how the phone shows it ("42g", "1.2k"), and the goal if
 * there is one. [amount] and [goal] are grams, or kcal for energy.
 */
@Serializable
data class WearMacro(
    val amount: Double,
    val text: String,
    val goal: Double? = null,
    val goalText: String? = null,
) {
    /** Progress toward the goal (0..1+), or null without one. */
    val progress: Float? get() = goal?.takeIf { it > 0 }?.let { (amount / it).toFloat() }
}

/** The latest glucose reading, already formatted in the user's unit ("6.4", "HI", "LO"). */
@Serializable
data class WearGlucose(
    val value: String,
    val unit: String,
    val mmolPerL: Double,
    val measuredAtEpochMs: Long,
    val band: GlucoseBand,
)

/**
 * Everything the watch shows, sent by the phone whenever meals, water or readings change. The
 * phone formats the numbers so the watch reads the same as the phone and its widget.
 */
@Serializable
data class WearSnapshot(
    /** The day these totals are for, "2026-09-26". */
    val date: String,
    val carbs: WearMacro,
    val protein: WearMacro,
    val fat: WearMacro,
    val kcal: WearMacro,
    val waterMl: Int,
    /** "750 ml", "1.25 L". */
    val waterText: String,
    val waterGoalMl: Int? = null,
    /** False once today's water reaches the phone's upper limit. */
    val canAddWater: Boolean = true,
    val glucose: WearGlucose? = null,
    /** When the phone made this snapshot. */
    val publishedAtEpochMs: Long,
    val version: Int = VERSION,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    /**
     * This snapshot as it stands on [today]: if it's from an earlier day (the phone hasn't been
     * heard from since midnight) the day's totals start again from zero; goals and the latest
     * glucose reading stay.
     */
    fun forDay(today: String): WearSnapshot = if (today == date) {
        this
    } else {
        copy(
            date = today,
            carbs = carbs.zeroed("0g"),
            protein = protein.zeroed("0g"),
            fat = fat.zeroed("0g"),
            kcal = kcal.zeroed("0"),
            waterMl = 0,
            waterText = "0 ml",
            canAddWater = true,
        )
    }

    companion object {
        const val VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        /** The snapshot from [text], or null if it's missing or unreadable. */
        fun decode(text: String?): WearSnapshot? {
            if (text.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
        }
    }
}

private fun WearMacro.zeroed(text: String) = copy(amount = 0.0, text = text)

/**
 * What the watch asks the phone to do, sent as short text: "water:250", "refresh". New kinds
 * (e.g. "dose:<id>" for a later insulin log) are added as another prefix; a phone that doesn't
 * know one gets [Unknown] and ignores it.
 */
sealed interface WearAction {
    /** Add this much water, in millilitres. */
    data class AddWater(val ml: Int) : WearAction

    /** Send the snapshot again now (the watch has none). */
    data object Refresh : WearAction

    data class Unknown(val text: String) : WearAction

    fun encode(): String = when (this) {
        is AddWater -> "$WATER:$ml"
        Refresh -> REFRESH
        is Unknown -> text
    }

    companion object {
        private const val WATER = "water"
        private const val REFRESH = "refresh"

        /** Largest single water amount the phone accepts from the watch. */
        const val MAX_WATER_ML = 2_000

        fun parse(text: String): WearAction {
            val trimmed = text.trim()
            val kind = trimmed.substringBefore(':')
            val argument = trimmed.substringAfter(':', missingDelimiterValue = "")
            return when (kind) {
                WATER -> argument.toIntOrNull()?.takeIf { it in 1..MAX_WATER_ML }?.let { AddWater(it) } ?: Unknown(trimmed)
                REFRESH -> if (argument.isEmpty()) Refresh else Unknown(trimmed)
                else -> Unknown(trimmed)
            }
        }

        fun parse(bytes: ByteArray): WearAction = parse(bytes.toString(Charsets.UTF_8))
    }
}
