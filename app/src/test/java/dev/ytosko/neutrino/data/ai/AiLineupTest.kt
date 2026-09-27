package dev.ytosko.neutrino.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiLineupTest {

    private fun c(id: String, inUse: Boolean = true, name: String = id) = AiConfig(id, "gemini", "gemini-3.8-flash", name, inUse = inUse)

    /** "P" Primary, "F1".. fallbacks, "-" unset, in list order. */
    private fun describe(list: List<AiConfig>): String {
        val roles = AiLineup.roles(list)
        return list.joinToString(" ") { cfg ->
            cfg.id + ":" + when (val r = roles.getValue(cfg.id)) {
                AiRole.Primary -> "P"
                is AiRole.Fallback -> "F${r.number}"
                AiRole.Unset -> "-"
            }
        }
    }

    @Test
    fun `roles follow the list order among models in use`() {
        assertEquals("a:P b:F1 x:- c:F2", describe(listOf(c("a"), c("b"), c("x", false), c("c"))))
        assertEquals("x:- a:P", describe(listOf(c("x", false), c("a"))))
    }

    @Test
    fun `making an unset model a fallback slots it in where it sits`() {
        val list = listOf(c("p"), c("f1"), c("u", false), c("f2"), c("f3"))
        assertEquals("p:P f1:F1 u:F2 f2:F3 f3:F4", describe(AiLineup.makeFallback(list, "u")))
    }

    @Test
    fun `an unset model above the primary becomes fallback 1`() {
        assertEquals("p:P u:F1 f:F2", describe(AiLineup.makeFallback(listOf(c("u", false), c("p"), c("f")), "u")))
    }

    @Test
    fun `making an unset model primary puts it on top and shifts the rest`() {
        val list = listOf(c("p"), c("f1"), c("u", false))
        assertEquals("u:P p:F1 f1:F2", describe(AiLineup.makePrimary(list, "u")))
    }

    @Test
    fun `a fallback made primary swaps places with the primary`() {
        val list = listOf(c("p"), c("f1"), c("f2"), c("f3"))
        assertEquals("f3:P f1:F1 f2:F2 p:F3", describe(AiLineup.makePrimary(list, "f3")))
    }

    @Test
    fun `the primary made fallback swaps with fallback 1`() {
        assertEquals("f1:P p:F1 f2:F2", describe(AiLineup.makeFallback(listOf(c("p"), c("f1"), c("f2")), "p")))
        assertEquals("a lone primary stays", "p:P u:-", describe(AiLineup.makeFallback(listOf(c("p"), c("u", false)), "p")))
    }

    @Test
    fun `unsetting or deleting the primary promotes fallback 1`() {
        val list = listOf(c("p"), c("f1"), c("f2"))
        assertEquals("p:- f1:P f2:F1", describe(AiLineup.unset(list, "p")))
        assertEquals("f1:P f2:F1", describe(AiLineup.delete(list, "p")))
    }

    @Test
    fun `dragging reorders and roles follow, unset models stay unset`() {
        val list = listOf(c("p"), c("f1"), c("u", false), c("f2"))
        assertEquals("f2:P p:F1 f1:F2 u:-", describe(AiLineup.move(list, 3, 0)))
        assertEquals("p:P u:- f1:F1 f2:F2", describe(AiLineup.move(list, 2, 1)))
    }

    @Test
    fun `automatic names count up, and a model keeps its own name when renamed`() {
        val list = listOf(c("a", name = "Gemini 3.8 Flash"), c("b", name = "Gemini 3.8 Flash 1"))
        assertEquals("Gemini 3.8 Flash 2", AiLineup.autoName(list, "Gemini 3.8 Flash"))
        assertEquals("Gemini 3.7 Flash", AiLineup.autoName(list, "Gemini 3.7 Flash"))
        assertEquals("renaming a to its own name is fine", "Gemini 3.8 Flash", AiLineup.autoName(list, "Gemini 3.8 Flash", exceptId = "a"))
    }

    @Test
    fun `the first model added becomes primary, later ones start unset`() {
        val one = AiLineup.upsert(emptyList(), c("a", inUse = false))
        assertEquals("a:P", describe(one))
        assertEquals("a:P b:-", describe(AiLineup.upsert(one, c("b"))))
    }
}
