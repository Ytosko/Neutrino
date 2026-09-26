package dev.ytosko.neutrino.wear.data

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import dev.ytosko.neutrino.wear.protocol.WearPaths
import kotlinx.coroutines.runBlocking

/** Woken by Wear OS when the phone sends a new snapshot; stores it and redraws the tile and complications. */
class SnapshotListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        val texts = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearPaths.TODAY }
            .mapNotNull { DataMapItem.fromDataItem(it.dataItem).dataMap.getString(WearPaths.SNAPSHOT_KEY) }
        if (texts.isEmpty()) return
        // Called on a background thread; the service stays alive until this returns.
        val stored = runBlocking { texts.map { SnapshotStore.save(applicationContext, it) }.any { it } }
        if (stored) PhoneLink.surfacesChanged(applicationContext)
    }
}
