package dev.ytosko.neutrino.data.ramadan

import dev.ytosko.neutrino.domain.RamadanCities
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class RamadanClientTest {

    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    @Test
    fun `reads each day's Sehri end and Iftar, and sends only the city's place and method`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"success":true,"data":{"ramadan_start":"2027-02-08","ramadan_end":"2027-03-08","days":[
                   {"day":1,"date":"2027-02-08","suhoor_ends":"05:19","fajr":"05:19","iftar":"17:49","maghrib":"17:49"},
                   {"day":2,"date":"2027-02-09","suhoor_ends":"05:19","iftar":"17:50"}]}}""",
            ).build(),
        )
        val client = RamadanClient(OkHttpClient(), Json { ignoreUnknownKeys = true }, server.url("/api").toString().trimEnd('/'))
        val days = client.schedule(RamadanCities.byId("dhaka")!!, 2027)
        assertEquals(2, days.size)
        assertEquals(LocalDate.of(2027, 2, 8), days[0].date)
        assertEquals(LocalTime.of(5, 19), days[0].sehriEnds)
        assertEquals(LocalTime.of(17, 50), days[1].iftar)

        val url = server.takeRequest().url
        assertEquals("/api/ramadan/2027", url.encodedPath)
        assertEquals("Karachi", url.queryParameter("method"))
        assertEquals("Hanafi", url.queryParameter("madhab"))
        assertEquals("Asia/Dhaka", url.queryParameter("timezone"))
        assertTrue(url.queryParameter("lat")!!.startsWith("23.8"))
    }
}
