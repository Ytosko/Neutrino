package dev.ytosko.neutrino.domain.voice

/**
 * The review page's conversation with the voice assistant, kept on the phone: each turn is what the
 * user said and what the app actually did ("You: "three hamburgers" → Added: Hamburger, 3 piece").
 * The last [max] turns go with every request so "no, I said three" or "undo that" make sense.
 * Nothing is saved; it ends with the page.
 */
class VoiceSession(private val max: Int = MAX_TURNS) {

    private val turns = ArrayDeque<String>()

    val lines: List<String> get() = turns.toList()

    /** A voice turn: what was said and what happened. */
    fun said(words: String, outcome: List<String>) {
        val clean = words.replace(Regex("[\"{}\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim().take(200)
        add("You: \"$clean\" → " + outcome.ifEmpty { listOf("Nothing changed") }.joinToString("; "))
    }

    /** Something the user changed by hand between voice turns. */
    fun byHand(change: String) = add("(By hand) $change")

    fun clear() = turns.clear()

    private fun add(line: String) {
        turns.addLast(line.take(400))
        while (turns.size > max) turns.removeFirst()
    }

    companion object {
        const val MAX_TURNS = 6
    }
}

/**
 * Whether the user's words give a new count, so the AI may change "3 pieces" to [newCount].
 * A weight, volume or calories ("maximum 100 grams") is not a count: then the count stays and the
 * weight is used. Relative words ("another", "one more", "half") also allow a change.
 */
object SpokenCount {

    // Not "more" or "less" alone: "no more than 100 grams" is a weight, not a new count.
    private val relative = listOf(
        "another", "one more", "two more", "a few more", "extra", "fewer", "half", "double", "twice",
        "আরেক", "অর্ধেক", "দ্বিগুণ",
    )

    private val words = mapOf(
        "one" to 1, "a single" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "এক" to 1, "একটা" to 1, "একটি" to 1, "দুই" to 2, "দুটো" to 2, "দুটি" to 2, "তিন" to 3, "তিনটা" to 3, "তিনটি" to 3,
        "চার" to 4, "চারটা" to 4, "পাঁচ" to 5, "পাঁচটা" to 5, "ছয়" to 6, "সাত" to 7, "আট" to 8, "নয়" to 9, "দশ" to 10,
    )

    /** Numbers followed by a weight, volume or energy unit: amounts, not counts. */
    private val measured = Regex(
        """(\d+(?:[.,]\d+)?)\s*(g\b|gm|gram|grams|gms|kg|kilo|ml|mls|milli|l\b|litre|liter|kcal|cal|calorie|calories|গ্রাম|মিলি|লিটার|ক্যালরি)""",
        RegexOption.IGNORE_CASE,
    )

    fun allows(text: String, newCount: Double): Boolean {
        // Bangla digits read as 0-9, so "১০০ গ্রাম" is a weight like "100 g".
        val lower = text.lowercase().map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }.joinToString("")
        if (relative.any { it in lower }) return true
        val bare = measured.replace(lower, " ")
        val digits = Regex("""\d+(?:[.,]\d+)?""").findAll(bare).mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }
        if (digits.any { it == newCount }) return true
        // Letters and marks: Bangla vowel signs are marks, so "তিনটা" stays one word.
        val tokens = bare.split(Regex("[^\\p{L}\\p{M}]+")).filter { it.isNotEmpty() }
        return words.any { (word, n) -> n.toDouble() == newCount && (if (' ' in word) word in bare else word in tokens) }
    }
}
