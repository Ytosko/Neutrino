package dev.ytosko.neutrino.wear.data

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import dev.ytosko.neutrino.wear.complication.CarbsComplicationService
import dev.ytosko.neutrino.wear.complication.GlucoseComplicationService
import dev.ytosko.neutrino.wear.protocol.WearAction
import dev.ytosko.neutrino.wear.protocol.WearPaths
import dev.ytosko.neutrino.wear.tile.NeutrinoTileService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Talks to Neutrino on the paired phone over the Wearable Data Layer. Nothing else: the watch app
 * has no internet access of its own.
 */
object PhoneLink {

    /**
     * Sends [action] to every reachable phone that has Neutrino. Returns false if none could be
     * reached (not paired, out of range, or the F-Droid phone app, which has no watch support).
     */
    suspend fun send(context: Context, action: WearAction): Boolean = withTimeoutOrNull(TIMEOUT_MS) {
        runCatching {
            val app = context.applicationContext
            val nodes = Wearable.getCapabilityClient(app)
                .getCapability(WearPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
            val payload = action.encode().toByteArray(Charsets.UTF_8)
            val client = Wearable.getMessageClient(app)
            nodes.count { node -> runCatching { client.sendMessage(node.id, WearPaths.ACTION, payload).await() }.isSuccess } > 0
        }.getOrDefault(false)
    } ?: false

    /**
     * Reads the phone's snapshot DataItem straight from the Data Layer (it stays there between
     * syncs), for when the watch app starts before the listener has heard anything.
     */
    suspend fun pull(context: Context): Boolean = withTimeoutOrNull(TIMEOUT_MS) {
        runCatching {
            val app = context.applicationContext
            val items = Wearable.getDataClient(app).dataItems.await()
            try {
                var stored = false
                for (item in items) {
                    if (item.uri.path != WearPaths.TODAY) continue
                    val text = DataMapItem.fromDataItem(item).dataMap.getString(WearPaths.SNAPSHOT_KEY) ?: continue
                    if (SnapshotStore.save(app, text)) stored = true
                }
                if (stored) surfacesChanged(app)
                stored
            } finally {
                items.release()
            }
        }.getOrDefault(false)
    } ?: false

    /** Asks the tile and complications to redraw from the stored snapshot. */
    fun surfacesChanged(context: Context) {
        val app = context.applicationContext
        runCatching { TileService.getUpdater(app).requestUpdate(NeutrinoTileService::class.java) }
        for (service in listOf(GlucoseComplicationService::class.java, CarbsComplicationService::class.java)) {
            runCatching { ComplicationDataSourceUpdateRequester.create(app, ComponentName(app, service)).requestUpdateAll() }
        }
    }

    private const val TIMEOUT_MS = 10_000L
}

/** Waits for a Play services [Task] without pulling in another library. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
