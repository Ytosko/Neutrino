package dev.ytosko.neutrino.wear.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.ytosko.neutrino.wear.protocol.WearSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.snapshotStore by preferencesDataStore(name = "phone_snapshot")

/**
 * The last snapshot the phone sent, kept on the watch so the tile, complications and app show it
 * straight away, even when the phone is out of reach.
 */
object SnapshotStore {
    private val SNAPSHOT = stringPreferencesKey("snapshot")

    /** Last clickable id of the tile's water button that was acted on, so a tap counts once. */
    private val HANDLED_TILE_CLICK = stringPreferencesKey("handled_tile_click")

    /** Today's view of the last snapshot (totals reset after midnight), or null before the first. */
    fun snapshot(context: Context): Flow<WearSnapshot?> = context.applicationContext.snapshotStore.data.map { prefs ->
        WearSnapshot.decode(prefs[SNAPSHOT])?.forDay(LocalDate.now().toString())
    }

    suspend fun current(context: Context): WearSnapshot? = snapshot(context).first()

    /** Stores [text] if it's a readable snapshot at least as new as the one kept. Returns true if stored. */
    suspend fun save(context: Context, text: String): Boolean {
        val incoming = WearSnapshot.decode(text) ?: return false
        var stored = false
        context.applicationContext.snapshotStore.edit { prefs ->
            val kept = WearSnapshot.decode(prefs[SNAPSHOT])
            if (kept == null || incoming.publishedAtEpochMs >= kept.publishedAtEpochMs) {
                prefs[SNAPSHOT] = text
                stored = true
            }
        }
        return stored
    }

    /** True the first time [clickId] is seen; false for a repeat of the same tap. */
    suspend fun claimTileClick(context: Context, clickId: String): Boolean {
        var fresh = false
        context.applicationContext.snapshotStore.edit { prefs ->
            if (prefs[HANDLED_TILE_CLICK] != clickId) {
                prefs[HANDLED_TILE_CLICK] = clickId
                fresh = true
            }
        }
        return fresh
    }
}
