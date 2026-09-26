package dev.ytosko.neutrino.wear

import android.content.Context

/**
 * The libre (F-Droid) build has no Google Play services, so there is no Wear OS companion: this
 * does nothing. Same shape as the full build's object, so the widget code doesn't need to know
 * which build it is.
 */
object WearBridge {
    @Suppress("UNUSED_PARAMETER")
    suspend fun publish(context: Context, force: Boolean = false) = Unit
}
