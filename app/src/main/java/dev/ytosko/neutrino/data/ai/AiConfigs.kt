package dev.ytosko.neutrino.data.ai

import kotlinx.serialization.Serializable

/**
 * One AI model the user set up: a provider, its key (stored encrypted, separately), a model and a
 * name. The user can have several, even several of the same provider.
 *
 * [inUse] models answer in list order: the first is the Primary, the rest are Fallback 1, 2, …
 * Models not in use keep their place in the list but aren't asked.
 */
@Serializable
data class AiConfig(
    val id: String,
    /** [AiProvider.id]. */
    val provider: String,
    val model: String,
    val name: String,
    /** The user typed [name]; otherwise it follows the model (see [AiLineup.autoName]). */
    val customName: Boolean = false,
    /** [PhotoDetail.id]. */
    val photoDetail: String = PhotoDetail.Standard.id,
    val inUse: Boolean = true,
    /** Last characters of the key, to tell keys apart ("…k6a"); never the key itself. */
    val keyTail: String = "",
    /**
     * OpenRouter: "I'm on a free plan". Only free models are offered, and requests stop before the
     * daily free limit instead of failing.
     */
    val freePlan: Boolean = true,
) {
    val providerEnum: AiProvider? get() = AiProvider.fromId(provider)
    val detail: PhotoDetail get() = PhotoDetail.fromId(photoDetail)
}

/** A model's place in the answering order. */
sealed interface AiRole {
    data object Primary : AiRole
    /** [number] starts at 1. */
    data class Fallback(val number: Int) : AiRole
    data object Unset : AiRole
}

/**
 * The rules for ordering and naming AI models. The list order is the answering order for models in
 * use; every change keeps exactly one Primary while any model is in use.
 */
object AiLineup {

    /** Most photo models one can set up (the voice model doesn't count). */
    const val MAX_MODELS = 4

    fun roles(list: List<AiConfig>): Map<String, AiRole> {
        var used = 0
        return list.associate { config ->
            config.id to if (!config.inUse) {
                AiRole.Unset
            } else {
                used++
                if (used == 1) AiRole.Primary else AiRole.Fallback(used - 1)
            }
        }
    }

    fun primary(list: List<AiConfig>): AiConfig? = list.firstOrNull { it.inUse }

    /** Models to ask, in order: Primary, then Fallback 1, 2, … */
    fun answeringOrder(list: List<AiConfig>): List<AiConfig> = list.filter { it.inUse }

    /**
     * A fallback swaps places with the Primary (the old Primary takes its number). A model not in use
     * moves to the top, and the old Primary becomes Fallback 1.
     */
    fun makePrimary(list: List<AiConfig>, id: String): List<AiConfig> {
        val index = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return list
        val target = list[index]
        val primaryIndex = list.indexOfFirst { it.inUse }
        if (target.inUse) {
            if (index == primaryIndex) return list
            return list.toMutableList().apply {
                this[index] = list[primaryIndex]
                this[primaryIndex] = target
            }
        }
        return listOf(target.copy(inUse = true)) + list.filterIndexed { i, _ -> i != index }
    }

    /**
     * The Primary swaps with Fallback 1 (no-op if there's none). A model not in use joins the fallback
     * order where it sits in the list, so the fallbacks after it move down one.
     */
    fun makeFallback(list: List<AiConfig>, id: String): List<AiConfig> {
        val index = list.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return list
        val target = list[index]
        if (!target.inUse) {
            val updated = list.toMutableList().apply { this[index] = target.copy(inUse = true) }
            // It sits above every model in use: make it a fallback by placing it right after the Primary.
            val primaryIndex = list.indexOfFirst { it.inUse }
            return if (primaryIndex > index) move(updated, index, primaryIndex) else updated
        }
        val inUse = list.withIndex().filter { it.value.inUse }
        if (inUse.firstOrNull()?.index != index) return list // already a fallback
        val next = inUse.getOrNull(1) ?: return list
        return list.toMutableList().apply {
            this[index] = next.value
            this[next.index] = target
        }
    }

    /** Stops using it; it keeps its place. If it was the Primary, Fallback 1 becomes Primary. */
    fun unset(list: List<AiConfig>, id: String): List<AiConfig> =
        list.map { if (it.id == id) it.copy(inUse = false) else it }

    fun delete(list: List<AiConfig>, id: String): List<AiConfig> = list.filterNot { it.id == id }

    /** Drag and drop: moves the model at [from] to [to]; roles follow the new order. */
    fun move(list: List<AiConfig>, from: Int, to: Int): List<AiConfig> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * A name for a model the user didn't name: its model name, then "name 1", "name 2", … if other
     * models already use that name. [exceptId] is the model being renamed.
     */
    fun autoName(list: List<AiConfig>, base: String, exceptId: String? = null): String {
        val taken = list.filter { it.id != exceptId }.mapTo(HashSet()) { it.name.lowercase() }
        if (base.lowercase() !in taken) return base
        var n = 1
        while ("$base $n".lowercase() in taken) n++
        return "$base $n"
    }

    /**
     * Adds a new model at the end, or updates [config] in place. A new model is used only when
     * nothing else is in use yet (it becomes the Primary); otherwise it starts unset.
     */
    fun upsert(list: List<AiConfig>, config: AiConfig): List<AiConfig> {
        val index = list.indexOfFirst { it.id == config.id }
        if (index >= 0) return list.toMutableList().apply { this[index] = config }
        return list + config.copy(inUse = list.none { it.inUse })
    }
}
