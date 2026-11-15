package dev.ivo.droiddrop

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings "Send clipboard": active while connected; a tap opens SendClipboardActivity either way. */
class ClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val state = ConnectionStatus.state.value
        tile.state = if (state is ConnectionState.Connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Send clipboard"
        tile.subtitle = if (state is ConnectionState.Connected) state.macName else "Not connected"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SendClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, REQUEST_SEND, intent, PendingIntent.FLAG_IMMUTABLE))
    }

    companion object {
        private const val REQUEST_SEND = 2

        /** Asks the system to rebind the tile so it shows the current connection state. */
        fun refresh(context: Context) {
            TileService.requestListeningState(context, ComponentName(context, ClipboardTileService::class.java))
        }
    }
}
