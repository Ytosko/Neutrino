package dev.ytosko.neutrino.domain

import java.time.LocalDate
import java.time.LocalTime

/**
 * A city for Ramadan times: where it is, and the prayer-time calculation its region usually uses
 * (Karachi and Hanafi in Bangladesh, India and Pakistan; Umm al-Qura in the Gulf…). Picking a city
 * instead of using GPS keeps Neutrino free of the location permission.
 */
data class RamadanCity(
    val id: String,
    val name: String,
    val country: String,
    val lat: Double,
    val lng: Double,
    val zone: String,
    val method: String,
    val madhab: String,
)

object RamadanCities {
    private fun bd(id: String, name: String, lat: Double, lng: Double) = RamadanCity(id, name, "Bangladesh", lat, lng, "Asia/Dhaka", "Karachi", "Hanafi")

    val all: List<RamadanCity> = listOf(
        bd("dhaka", "Dhaka", 23.8103, 90.4125),
        bd("chattogram", "Chattogram", 22.3569, 91.7832),
        bd("khulna", "Khulna", 22.8456, 89.5403),
        bd("rajshahi", "Rajshahi", 24.3745, 88.6042),
        bd("sylhet", "Sylhet", 24.8949, 91.8687),
        bd("barishal", "Barishal", 22.7010, 90.3535),
        bd("rangpur", "Rangpur", 25.7439, 89.2752),
        bd("mymensingh", "Mymensingh", 24.7471, 90.4203),
        bd("cumilla", "Cumilla", 23.4607, 91.1809),
        bd("gazipur", "Gazipur", 23.9999, 90.4203),
        bd("narayanganj", "Narayanganj", 23.6238, 90.5000),
        bd("coxsbazar", "Cox's Bazar", 21.4272, 92.0058),
        RamadanCity("kolkata", "Kolkata", "India", 22.5726, 88.3639, "Asia/Kolkata", "Karachi", "Hanafi"),
        RamadanCity("delhi", "Delhi", "India", 28.6139, 77.2090, "Asia/Kolkata", "Karachi", "Hanafi"),
        RamadanCity("mumbai", "Mumbai", "India", 19.0760, 72.8777, "Asia/Kolkata", "Karachi", "Hanafi"),
        RamadanCity("hyderabad", "Hyderabad", "India", 17.3850, 78.4867, "Asia/Kolkata", "Karachi", "Hanafi"),
        RamadanCity("karachi", "Karachi", "Pakistan", 24.8607, 67.0011, "Asia/Karachi", "Karachi", "Hanafi"),
        RamadanCity("lahore", "Lahore", "Pakistan", 31.5204, 74.3587, "Asia/Karachi", "Karachi", "Hanafi"),
        RamadanCity("islamabad", "Islamabad", "Pakistan", 33.6844, 73.0479, "Asia/Karachi", "Karachi", "Hanafi"),
        RamadanCity("riyadh", "Riyadh", "Saudi Arabia", 24.7136, 46.6753, "Asia/Riyadh", "UmmAlQura", "Shafi"),
        RamadanCity("jeddah", "Jeddah", "Saudi Arabia", 21.4858, 39.1925, "Asia/Riyadh", "UmmAlQura", "Shafi"),
        RamadanCity("makkah", "Makkah", "Saudi Arabia", 21.3891, 39.8579, "Asia/Riyadh", "UmmAlQura", "Shafi"),
        RamadanCity("madinah", "Madinah", "Saudi Arabia", 24.5247, 39.5692, "Asia/Riyadh", "UmmAlQura", "Shafi"),
        RamadanCity("dubai", "Dubai", "UAE", 25.2048, 55.2708, "Asia/Dubai", "Dubai", "Shafi"),
        RamadanCity("abudhabi", "Abu Dhabi", "UAE", 24.4539, 54.3773, "Asia/Dubai", "Dubai", "Shafi"),
        RamadanCity("doha", "Doha", "Qatar", 25.2854, 51.5310, "Asia/Qatar", "UmmAlQura", "Shafi"),
        RamadanCity("kuwait", "Kuwait City", "Kuwait", 29.3759, 47.9774, "Asia/Kuwait", "UmmAlQura", "Shafi"),
        RamadanCity("muscat", "Muscat", "Oman", 23.5880, 58.3829, "Asia/Muscat", "UmmAlQura", "Shafi"),
        RamadanCity("kualalumpur", "Kuala Lumpur", "Malaysia", 3.1390, 101.6869, "Asia/Kuala_Lumpur", "MuslimWorldLeague", "Shafi"),
        RamadanCity("singapore", "Singapore", "Singapore", 1.3521, 103.8198, "Asia/Singapore", "MuslimWorldLeague", "Shafi"),
        RamadanCity("jakarta", "Jakarta", "Indonesia", -6.2088, 106.8456, "Asia/Jakarta", "MuslimWorldLeague", "Shafi"),
        RamadanCity("istanbul", "Istanbul", "Türkiye", 41.0082, 28.9784, "Europe/Istanbul", "MuslimWorldLeague", "Hanafi"),
        RamadanCity("cairo", "Cairo", "Egypt", 30.0444, 31.2357, "Africa/Cairo", "Egyptian", "Shafi"),
        RamadanCity("london", "London", "United Kingdom", 51.5074, -0.1278, "Europe/London", "MoonsightingCommittee", "Hanafi"),
        RamadanCity("birmingham", "Birmingham", "United Kingdom", 52.4862, -1.8904, "Europe/London", "MoonsightingCommittee", "Hanafi"),
        RamadanCity("manchester", "Manchester", "United Kingdom", 53.4808, -2.2426, "Europe/London", "MoonsightingCommittee", "Hanafi"),
        RamadanCity("newyork", "New York", "United States", 40.7128, -74.0060, "America/New_York", "NorthAmerica", "Shafi"),
        RamadanCity("toronto", "Toronto", "Canada", 43.6532, -79.3832, "America/Toronto", "NorthAmerica", "Shafi"),
        RamadanCity("sydney", "Sydney", "Australia", -33.8688, 151.2093, "Australia/Sydney", "MuslimWorldLeague", "Shafi"),
    )

    fun byId(id: String?): RamadanCity? = all.firstOrNull { it.id == id }
}

/** One day of Ramadan: when Sehri ends (Fajr) and Iftar (Maghrib), in the city's time. */
data class RamadanDay(val date: LocalDate, val sehriEnds: LocalTime, val iftar: LocalTime)

/**
 * During Ramadan, medicine reminders move with the fast: a morning one to 30 minutes before Sehri
 * ends, a midday or afternoon one to Iftar; evening and night ones stay as they are.
 */
object RamadanMedicine {
    private val middayFrom: LocalTime = LocalTime.of(11, 0)
    private val eveningFrom: LocalTime = LocalTime.of(17, 0)

    fun shift(time: LocalTime, day: RamadanTimes?): LocalTime = when {
        day == null -> time
        time < middayFrom -> day.sehriEnds.minusMinutes(30)
        time < eveningFrom -> day.iftar
        else -> time
    }
}
