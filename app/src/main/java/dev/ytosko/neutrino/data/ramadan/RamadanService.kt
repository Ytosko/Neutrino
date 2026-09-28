package dev.ytosko.neutrino.data.ramadan

import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.call
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.RamadanCity
import dev.ytosko.neutrino.domain.RamadanDay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalTime

@Serializable
private data class RamadanReply(val data: RamadanData? = null)

@Serializable
private data class RamadanData(
    @SerialName("ramadan_start") val start: String? = null,
    @SerialName("ramadan_end") val end: String? = null,
    val days: List<RamadanDayJson> = emptyList(),
)

@Serializable
private data class RamadanDayJson(
    val date: String,
    @SerialName("suhoor_ends") val suhoorEnds: String? = null,
    val fajr: String? = null,
    val iftar: String? = null,
    val maghrib: String? = null,
)

/**
 * Ramadan's dates and each day's times, from Ummah API (ummahapi.com, free, no key): Sehri ends at
 * Fajr and Iftar is at Maghrib. Only the chosen city's coordinates and the year are sent; the
 * schedule is kept on the phone, so it works offline for the whole month.
 */
class RamadanClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://ummahapi.com/api",
) {
    /** The Ramadan that falls in Gregorian [year] for [city]; empty if there's none. */
    suspend fun schedule(city: RamadanCity, year: Int): List<RamadanDay> {
        val url = "$baseUrl/ramadan/$year".toHttpUrl().newBuilder()
            .addQueryParameter("lat", city.lat.toString())
            .addQueryParameter("lng", city.lng.toString())
            .addQueryParameter("method", city.method)
            .addQueryParameter("madhab", city.madhab)
            .addQueryParameter("timezone", city.zone)
            .build()
        val (code, body) = http.call(Request.Builder().url(url).header("Accept", "application/json").build())
        if (code !in 200..299) throw AiException.Unexpected(code)
        val data = runCatching { json.decodeFromString<RamadanReply>(body).data }.getOrNull() ?: throw AiException.NoResult()
        return data.days.mapNotNull { day ->
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@mapNotNull null
            val sehri = (day.suhoorEnds ?: day.fajr)?.let(::time) ?: return@mapNotNull null
            val iftar = (day.iftar ?: day.maghrib)?.let(::time) ?: return@mapNotNull null
            RamadanDay(date, sehri, iftar)
        }
    }

    private fun time(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim().take(5)) }.getOrNull()
}

/**
 * Keeps the Ramadan schedule for the user's city on the phone: fetched when Ramadan mode is turned
 * on or the city changes, and again once the saved Ramadan has passed (for the next one). Called
 * when the app starts; does nothing when the saved schedule is still ahead or running.
 */
class RamadanSync(private val settings: SettingsRepository, private val client: RamadanClient) {

    /** True when a schedule covering today or a later day is saved (fetched now or before). */
    suspend fun ensure(today: LocalDate = LocalDate.now(), force: Boolean = false): Boolean {
        val current = settings.settings.first()
        if (!current.ramadan) return false
        val city = current.ramadanCity ?: return false
        val saved = current.ramadanDays
        if (!force && saved.isNotEmpty() && saved.last().date >= today && current.ramadanCityOfDays == city.id) return true
        // This year's Ramadan, or next year's once this one is over.
        var days = client.schedule(city, today.year)
        if (days.isEmpty() || days.last().date < today) days = client.schedule(city, today.year + 1)
        if (days.isEmpty()) return false
        settings.saveRamadanDays(city.id, days)
        return true
    }
}
