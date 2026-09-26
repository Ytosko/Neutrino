package dev.ytosko.neutrino.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.ytosko.neutrino.appContainer
import dev.ytosko.neutrino.wear.protocol.WearAction
import dev.ytosko.neutrino.wear.protocol.WearPaths
import dev.ytosko.neutrino.widget.NeutrinoWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId

/**
 * Taps on the watch (e.g. "+250 ml") arrive here as a Data Layer message from the user's own
 * watch. Wear OS starts this service only for messages from this app on a paired watch.
 */
class WearActionService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.ACTION) return
        val action = WearAction.parse(event.data)
        // Called on a background thread; the service stays alive until this returns.
        runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
                runCatching {
                    when (action) {
                        is WearAction.AddWater -> {
                            addWater(action.ml)
                            NeutrinoWidget.refresh(applicationContext)
                        }
                        WearAction.Refresh -> WearBridge.publish(applicationContext, force = true)
                        is WearAction.Unknown -> Unit
                    }
                }
            }
        }
    }

    /** Same as the widget's +250 ml: never past the day's upper limit. */
    private suspend fun addWater(ml: Int) {
        val meals = applicationContext.appContainer.meals
        val zone = ZoneId.systemDefault()
        val water = meals.observeDay(LocalDate.now(zone), zone).first().waterMl
        if (water + ml <= NeutrinoWidget.MAX_WATER_ML) {
            meals.addWater(ml, zone)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
