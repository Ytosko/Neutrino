package dev.ytosko.neutrino.data.ai

/**
 * Asks the Primary model, and when it fails (limit, key, network, server, unusable answer) Fallback
 * 1, then 2, … A model that answers stops the chain, even if it found nothing: another model
 * wouldn't find food that isn't there, and it would cost twice.
 */
object AiChain {

    /**
     * Runs [block] on each model of [chain] in order until one succeeds; returns that model and its
     * result. [shouldFallBack] decides which failures move on; the last failure is rethrown.
     * [onAttempt] reports each model as it's tried (e.g. to show its name). Null if [chain] is empty.
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
                last = e
                if (!shouldFallBack(e)) throw e
            }
        }
        throw last ?: return null
    }
}
