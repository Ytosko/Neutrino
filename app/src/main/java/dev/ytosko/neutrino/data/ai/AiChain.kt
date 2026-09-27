package dev.ytosko.neutrino.data.ai

/**
 * Asks the Primary model, and when it fails (limit, key, network, server, unusable answer) Fallback
 * 1, then 2, … A model that answers stops the chain, even if it found nothing: another model
 * wouldn't find food that isn't there, and it would cost twice.
 */
object AiChain {

    /**
     * Swaps a model that's no longer offered for its replacement (see [AiException.ModelGone]) and
     * saves the change; returns the new setup, or null to leave it. Set once by the app.
     */
    @Volatile var repair: (suspend (AiConfig, AiException.ModelGone) -> AiConfig?)? = null

    /**
     * Runs [block] on each model of [chain] in order until one succeeds; returns that model and its
     * result. [shouldFallBack] decides which failures move on; the last failure is rethrown.
     * [onAttempt] reports each model as it's tried (e.g. to show its name). Null if [chain] is empty.
     * A model that's gone is replaced (via [repair]) and the replacement asked once, in its place.
     */
    suspend fun <T> run(
        chain: List<Pair<AiConfig, String>>,
        clients: Map<AiProvider, AiClient>,
        shouldFallBack: (AiException) -> Boolean = { true },
        onAttempt: (AiConfig) -> Unit = {},
        block: suspend (client: AiClient, apiKey: String, config: AiConfig) -> T,
    ): Pair<AiConfig, T>? {
        var last: AiException? = null
        for ((config, key) in chain) {
            val client = config.providerEnum?.let(clients::get) ?: continue
            onAttempt(config)
            try {
                return config to block(client, key, config)
            } catch (e: AiException) {
                val replaced = (e as? AiException.ModelGone)?.let { gone -> repair?.invoke(config, gone) }
                if (replaced != null) {
                    onAttempt(replaced)
                    try {
                        return replaced to block(client, key, replaced)
                    } catch (again: AiException) {
                        last = again
                        if (!shouldFallBack(again)) throw again
                        continue
                    }
                }
                last = e
                if (!shouldFallBack(e)) throw e
            }
        }
        throw last ?: return null
    }
}
