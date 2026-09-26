package dev.ytosko.neutrino.data.medicine

import dev.ytosko.neutrino.data.ai.call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A medicine MedEx knows: "Napa", "500 mg", "Tablet", and its page (for the generic name). */
data class MedexSuggestion(val name: String, val strength: String?, val form: String?, val url: String)

/**
 * Medicine names from MedEx (medex.com.bd), a Bangladeshi medicine index, for the user's own,
 * non-commercial use: suggestions while they type a medicine's name, and the generic (group) of
 * the one they pick. Only what they type is sent, one request per search; nothing else is read.
 * The results are HTML, parsed here; sponsored results are skipped.
 */
class MedexClient(
    private val http: OkHttpClient,
    private val userAgent: String,
    private val baseUrl: String = "https://medex.com.bd",
) {

    /** Up to [limit] suggestions; empty when there are none. Throws on network errors. */
    suspend fun search(query: String, limit: Int = 12): List<MedexSuggestion> {
        val url = "$baseUrl/ajax/search".toHttpUrl().newBuilder()
            .addQueryParameter("searchtype", "search")
            .addQueryParameter("searchkey", query.trim())
            .build()
        val (code, body) = http.call(Request.Builder().url(url).header("User-Agent", userAgent).build())
        if (code !in 200..299) throw MedexException(code)
        return parseSearch(body).take(limit)
    }

    /** The generic name on a medicine's MedEx page, e.g. "Paracetamol"; null if not found. */
    suspend fun generic(suggestion: MedexSuggestion): String? {
        if (!suggestion.url.startsWith("$baseUrl/")) return null
        val (code, body) = http.call(Request.Builder().url(suggestion.url).header("User-Agent", userAgent).build())
        if (code !in 200..299) return null
        return parseGeneric(body)
    }

    companion object {
        private val RESULT = Regex(
            """<a href="([^"]+)" class="lsri( ad)?">.*?<li title="([^"]*)">.*?<span>\s*(.*?)</span>\s*</li>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val STRENGTH = Regex("""<span class="sr-strength">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
        private val GENERIC = Regex("""title="Generic Name"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)

        internal fun parseSearch(html: String): List<MedexSuggestion> =
            RESULT.findAll(html).mapNotNull { m ->
                val (url, ad, form, inner) = m.destructured
                if (ad.isNotEmpty() || "/brands/" !in url) return@mapNotNull null
                val strength = STRENGTH.find(inner)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotEmpty() }
                val name = clean(inner.replace(STRENGTH, " ")).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                MedexSuggestion(name, strength, clean(form).takeIf { it.isNotEmpty() }, url)
            }.distinctBy { Triple(it.name.lowercase(), it.strength, it.form) }.toList()

        internal fun parseGeneric(html: String): String? =
            GENERIC.find(html)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotEmpty() }

        private fun clean(fragment: String): String =
            fragment.replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&").replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}

class MedexException(val code: Int) : Exception("MedEx returned $code")
