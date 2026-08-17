package com.msnguard.vpn

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: shows tunnel state and toggles it.
 *
 * Deliberately thin. It used to carry a private copy of MainActivity's entire
 * settings layer — thirteen accessor methods, seven enums and fourteen pref keys
 * — none of which anything called, because the tile builds its config the same
 * way every other entry point does: [CoreConfig.json]. Duplicated defaults are
 * worse than none, since the copy silently stops matching the original.
 */
class MsnGuardTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        toggleConnection()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isConnected = TunnelStatus.isActive()
        tile.state = if (isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.label = getString(R.string.vpn_tile_label)
        tile.setSubtitleCompat(
            if (isConnected) getString(R.string.vpn_connected) else getString(R.string.vpn_disconnected)
        )
        tile.updateTile()
    }

    private fun toggleConnection() {
        val tile = qsTile ?: return

        if (TunnelStatus.isActive()) {
            startService(serviceIntent(MsnGuardVpnService.ACTION_DISCONNECT))
            tile.state = Tile.STATE_INACTIVE
            tile.setSubtitleCompat(getString(R.string.vpn_disconnected))
            tile.updateTile()
            return
        }

        // VPN mode is the only mode, so Android's VPN consent is always required
        // before the service may build a TUN. A TileService cannot host that
        // dialog, so when consent is missing the only useful action is to open the
        // app — which is exactly what this used to skip, leaving the tap looking
        // broken with nothing but a logcat line to explain it.
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }

        startForegroundService(
            serviceIntent(MsnGuardVpnService.ACTION_CONNECT)
                .putExtra(MsnGuardVpnService.EXTRA_CONFIG, CoreConfig.json(this))
        )
        tile.state = Tile.STATE_ACTIVE
        tile.setSubtitleCompat(getString(R.string.vpn_connecting))
        tile.updateTile()
    }

    private fun serviceIntent(action: String): Intent =
        Intent(this, MsnGuardVpnService::class.java).setAction(action)

    /**
     * Opens MainActivity and collapses the shade.
     *
     * API 34 made the Intent overload of startActivityAndCollapse throw
     * UnsupportedOperationException and replaced it with a PendingIntent one, so
     * both forms are needed while minSdk is 26.
     */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /** Tile.setSubtitle is API 29+; below that the tile simply has no subtitle. */
    private fun Tile.setSubtitleCompat(value: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = value
    }
}
