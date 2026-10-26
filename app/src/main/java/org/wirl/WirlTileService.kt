package org.wirl

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The Quick Settings tile. Tap turns Wirl on or off, and stays in step with the bubble toggle in Settings. Needs setup
 * opens the accessibility settings or the setup checklist. Long-press opens Wirl's Settings (see MainActivity's filter).
 */
class WirlTileService : TileService() {
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_BUBBLE_ENABLED) refresh()
    }

    override fun onStartListening() {
        Prefs(this).plain.registerOnSharedPreferenceChangeListener(prefsListener)
        refresh()
    }

    override fun onStopListening() {
        Prefs(this).plain.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onClick() {
        val prefs = Prefs(this)
        val state = currentState(prefs)
        val next = WirlTileLogic.afterTap(state, prefs.bubbleEnabled)
        if (next == null) {
            openSetup(prefs)
        } else {
            prefs.bubbleEnabled = next
        }
        refresh()
    }

    private fun currentState(prefs: Prefs): TileState = WirlTileLogic.stateFor(
        accessibilityOn = SetupActivity.isAccessibilityOn(this),
        apiKeySaved = prefs.apiKey.isNotBlank(),
        bubbleEnabled = prefs.bubbleEnabled,
    )

    private fun refresh() {
        val tile = qsTile ?: return
        val prefs = Prefs(this)
        val state = currentState(prefs)
        tile.label = getString(R.string.tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_wirl_glyph)
        tile.state = when (state) {
            TileState.ON -> Tile.STATE_ACTIVE
            TileState.OFF -> Tile.STATE_INACTIVE
            TileState.NEEDS_SETUP -> Tile.STATE_UNAVAILABLE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(
                when (state) {
                    TileState.ON -> R.string.tile_on
                    TileState.OFF -> R.string.tile_off
                    TileState.NEEDS_SETUP -> R.string.tile_needs_setup
                },
            )
        }
        tile.updateTile()
    }

    /** Accessibility is the first missing piece; otherwise the API key is, and the checklist shows it. */
    private fun openSetup(prefs: Prefs) {
        val intent = if (!SetupActivity.isAccessibilityOn(this)) {
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        } else {
            Intent(this, SetupActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
