package ru.uvw.byecensor

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ru.uvw.byecensor.store.Store

/**
 * The proxy as a quick settings tile.
 *
 * The tile switches the one thing the notification cannot: whether traffic goes through the server.
 * Active means "through the server", inactive means "direct" — the same pair of states as the big
 * button on the main screen, because two places cannot mean two different things.
 */
class ProxyTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        paint()
    }

    override fun onClick() {
        super.onClick()
        val store = Store(this)
        store.viaServer = !store.viaServer
        // one command that rebuilds the kernel: stopping and starting separately races with
        // ourselves and leaves the proxy down
        runCatching { ProxyService.reload(this) }
        paint()
    }

    private fun paint() {
        val tile = qsTile ?: return
        val store = Store(this)
        val viaServer = store.viaServer
        tile.state = if (viaServer) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            if (viaServer) R.drawable.ic_stat_proxy else R.drawable.ic_stat_direct,
        )
        tile.label = "Bye, Censor!"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !viaServer -> "напрямую"
                else -> "через ${store.selected()?.title() ?: "сервер"}"
            }
        }
        tile.updateTile()
    }
}
