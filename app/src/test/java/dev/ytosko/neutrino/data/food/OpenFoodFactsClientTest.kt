package dev.ytosko.neutrino.data.food

import dev.ytosko.neutrino.domain.food.FoodSource
import dev.ytosko.neutrino.domain.food.FoodUnit
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

class OpenFoodFactsClientTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun client() = OpenFoodFactsClient(
        http = OkHttpClient(),
        json = json,
        userAgent = "Neutrino/test",
        searchUrl = server.url("/s").toString().trimEnd('/'),
        classicUrl = server.url("/c").toString().trimEnd('/'),
    )

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `search service hits become packaged foods with serving and brand`() = runTest {
        respond(
            200,
            """{"hits":[
                {"code":"123","product_name":"Hot Chanachur","brands":["Pran"],"quantity":"150 g","serving_quantity":30,
                 "nutriments":{"energy-kcal_100g":551,"proteins_100g":12,"carbohydrates_100g":48,"fat_100g":35}},
                {"code":"456","product_name":"Mango juice","brands":["Frooto"],"quantity":"250 ml",
                 "nutriments":{"energy_100g":251,"carbohydrates_100g":14.5}},
                {"code":"789","product_name":"No nutrition"}
            ]}""",
        )
        val foods = client().search("chanachur")
        assertEquals(2, foods.size)
        val chanachur = foods[0]
        assertEquals("Hot Chanachur (Pran)", chanachur.name)
        assertEquals(FoodSource.OpenFoodFacts, chanachur.source)
        assertEquals(165.3, chanachur.nutrition(1.0, FoodUnit.Serving)!!.calories, 0.001)

        val juice = foods[1]
        assertEquals(60.0, juice.per100g.calories, 0.1) // kJ converted to kcal
        assertTrue("liquids can be measured in ml", FoodUnit.Milliliter in juice.availableUnits)

        val request = server.takeRequest()
        assertEquals("Neutrino/test", request.headers["User-Agent"])
        assertTrue(request.url.encodedPath.endsWith("/s/search"))
    }

    @Test
    fun `falls back to the classic endpoint when the search service fails`() = runTest {
        respond(503, "<html>down</html>")
        respond(200, """{"products":[{"code":"1","product_name":"Biscuit","brands":"Olympic, Other","nutriments":{"energy-kcal_100g":480}}]}""")
        val foods = client().search("biscuit")
        assertEquals("Biscuit (Olympic)", foods.single().name)
        server.takeRequest()
        assertTrue(server.takeRequest().url.encodedPath.endsWith("/c/cgi/search.pl"))
    }
}
