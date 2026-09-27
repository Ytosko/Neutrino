package dev.ytosko.neutrino.domain.voice

import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.ScannedItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** What the user asked for in one spoken sentence, as the AI understood it. */
data class VoiceCommand(
    /** A short sentence to say back, in the user's language. Never contains glucose numbers. */
    val reply: String,
    val items: List<ItemChange> = emptyList(),
    /** When the meal was eaten (new meal), or the new time the user asked for (editing). */
    val mealTime: LocalDateTime? = null,
    val waterMl: Int = 0,
    val glucose: SpokenGlucose? = null,
    val medicines: List<SpokenMedicine> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty() && mealTime == null && waterMl <= 0 && glucose == null && medicines.isEmpty()
    val hasMeal: Boolean get() = items.isNotEmpty()
}

sealed interface ItemChange {
    data class Add(val item: ScannedItem) : ItemChange
    /** [index] is the 0-based line on the review page; [item] holds the new total amount. */
    data class Set(val index: Int, val item: ScannedItem) : ItemChange
    data class Remove(val index: Int) : ItemChange
}

enum class SpokenRelation { General, Fasting, BeforeMeal, AfterMeal, Bedtime }

/** [unit] as said: "mmol", "mgdl", or null when the user didn't say. */
data class SpokenGlucose(val value: Double, val unit: String?, val relation: SpokenRelation, val time: LocalDateTime?) {
    /** mmol/L; with no unit said, a value above 35 can only be mg/dL. */
    val mmolPerL: Double
        get() = when {
            unit == "mgdl" -> value / MGDL_PER_MMOL
            unit == "mmol" -> value
            value > 35 -> value / MGDL_PER_MMOL
            else -> value
        }

    private companion object {
        const val MGDL_PER_MMOL = 18.0182
    }
}

data class SpokenMedicine(val name: String, val strength: String, val amount: Double, val time: LocalDateTime?)

/** Reads the AI's JSON reply. Lenient: bad or missing parts are dropped rather than failing. */
object VoiceCommandParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * [itemCount] is how many lines the meal had, so an edit can't point past them. Times in the
     * future are taken as the day before ("rice at 9 pm", said in the morning, was last night).
     */
    fun parse(text: String, now: LocalDateTime, itemCount: Int): VoiceCommand? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null

        val items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val action = item.string("action")?.lowercase().orEmpty()
            val index = item.number("item").toInt() - 1
            val scanned = item.scanned()
            when (action) {
                "add" -> scanned?.let { ItemChange.Add(it) }
                "set" -> if (index in 0 until itemCount && scanned != null) ItemChange.Set(index, scanned) else scanned?.let { ItemChange.Add(it) }
                "remove" -> if (index in 0 until itemCount) ItemChange.Remove(index) else null
                else -> null
            }
        }

        val glucoseValue = obj.number("glucose_value")
        val glucose = if (glucoseValue > 0) {
            SpokenGlucose(
                value = glucoseValue,
                unit = obj.string("glucose_unit")?.lowercase()?.replace(Regex("[^a-z]"), "")?.let {
                    when {
                        it.startsWith("mg") -> "mgdl"
                        it.startsWith("mmol") -> "mmol"
                        else -> null
                    }
                },
                relation = when (obj.string("glucose_relation")?.lowercase()?.replace(Regex("[^a-z]"), "")) {
                    "fasting" -> SpokenRelation.Fasting
                    "beforemeal" -> SpokenRelation.BeforeMeal
                    "aftermeal" -> SpokenRelation.AfterMeal
                    "bedtime" -> SpokenRelation.Bedtime
                    else -> SpokenRelation.General
                },
                time = time(obj.string("glucose_time"), now),
            )
        } else {
            null
        }

        val medicines = (obj["medicines"] as? JsonArray).orEmpty().mapNotNull { element ->
            val m = element as? JsonObject ?: return@mapNotNull null
            val name = m.string("name")?.take(60)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SpokenMedicine(name, m.string("strength").orEmpty().take(20), m.number("amount"), time(m.string("time"), now))
        }

        return VoiceCommand(
            reply = obj.string("reply").orEmpty().take(200),
            items = items,
            mealTime = time(obj.string("meal_time"), now),
            waterMl = obj.number("water_ml").toInt().coerceIn(0, 5_000),
            glucose = glucose,
            medicines = medicines,
        )
    }

    private val dateTime = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})""")
    private val timeOnly = Regex("""^(\d{1,2}):(\d{2})$""")

    /** "2026-09-28 14:00" or "14:00" (today); blank means "not said". */
    fun time(raw: String?, now: LocalDateTime): LocalDateTime? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parsed = dateTime.find(text)?.destructured?.let { (y, mo, d, h, mi) ->
            runCatching { LocalDateTime.of(LocalDate.of(y.toInt(), mo.toInt(), d.toInt()), LocalTime.of(h.toInt(), mi.toInt())) }.getOrNull()
        } ?: timeOnly.find(text)?.destructured?.let { (h, mi) ->
            runCatching { LocalDateTime.of(now.toLocalDate(), LocalTime.of(h.toInt(), mi.toInt())) }.getOrNull()
        } ?: return null
        return when {
            parsed.isAfter(now.plusMinutes(5)) -> parsed.minusDays(1).takeIf { !it.isAfter(now) }
            parsed.isBefore(now.minusDays(7)) -> null
            else -> parsed
        }
    }

    private fun JsonObject.scanned(): ScannedItem? {
        val name = string("name")?.take(80)?.takeIf { it.isNotBlank() } ?: return null
        return ScannedItem(
            name = name,
            quantity = number("quantity").takeIf { it > 0 } ?: 1.0,
            unit = string("unit").orEmpty().ifBlank { "serving" },
            grams = number("grams"),
            nutrition = Nutrition(number("calories"), number("protein_g"), number("carbs_g"), number("fat_g")),
        )
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()

    private fun JsonObject.number(key: String): Double {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull ?: return 0.0
        val value = Regex("-?\\d+(?:\\.\\d+)?").find(raw)?.value?.toDoubleOrNull() ?: return 0.0
        return if (value.isFinite() && value > 0) value else 0.0
    }
}

/** A medicine in the user's list, as far as matching needs it. */
data class KnownMedicine(val id: String, val name: String, val generic: String?, val strength: String?)

/**
 * Finds what the user meant in their own medicine list, by brand or generic name ("Metformin 500"
 * finds Oramet SR 500 when its generic is metformin). Never matches anything outside the list.
 */
object MedicineMatcher {

    fun match(spoken: SpokenMedicine, list: List<KnownMedicine>): List<KnownMedicine> {
        val words = spoken.name.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 3 }
        val numbers = numbersIn(spoken.strength + " " + spoken.name)
        if (words.isEmpty()) return emptyList()
        val byName = list.filter { medicine ->
            val names = listOfNotNull(medicine.name, medicine.generic)
                .flatMap { it.lowercase().split(Regex("[^\\p{L}]+")) }
                .filter { it.length >= 3 }
            words.any { word -> names.any { similar(word, it) } }
        }
        // A strength said ("500") narrows it down, but a slip in the number shouldn't lose the medicine.
        return byName.filterStrength(numbers).ifEmpty { byName }
    }

    private fun List<KnownMedicine>.filterStrength(numbers: Set<String>): List<KnownMedicine> =
        if (numbers.isEmpty()) this else filter { m -> numbersIn("${m.strength.orEmpty()} ${m.name}").any { it in numbers } }

    private fun numbersIn(text: String): Set<String> = Regex("\\d+(?:\\.\\d+)?").findAll(text).map { it.value }.toSet()

    /** Same word, one starting the other, or a small spelling slip (speech gets brand names a bit wrong). */
    internal fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) return true
        if (minOf(a.length, b.length) < 5) return false
        return distance(a, b) <= if (maxOf(a.length, b.length) >= 6) 2 else 1
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
