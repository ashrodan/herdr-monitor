package io.github.ashrodan.herdrmonitor

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

// Quick settings tile "Herdr": "2 need you" when any, else "4 working".
// A tap opens Sessions filtered to Needs you.
class HerdrTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val t = qsTile ?: return
        val d = Widget.fromJson(app.prefs.widgetJson)
        t.label = "Herdr"
        t.subtitle = tileSubtitle(d)
        t.state = if ((d?.needsYou ?: 0) > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val i = Intent(this, MainActivity::class.java).setData(NEEDS_YOU_URI)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }

    companion object {
        // Ask Android to call onStartListening again (when the counts change).
        fun refresh(c: Context) {
            runCatching { TileService.requestListeningState(c, ComponentName(c, HerdrTile::class.java)) }
        }
    }
}

// Quick settings tile "Quiet agents": when on, only the Needs you channel
// alerts (no Running or Finished notifications).
class QuietTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        show()
    }

    override fun onClick() {
        super.onClick()
        setQuiet(this, !app.prefs.quiet)
        show()
    }

    private fun show() {
        val t = qsTile ?: return
        val on = app.prefs.quiet
        t.label = "Quiet agents"
        t.subtitle = if (on) "Only needs-you" else "Off"
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}

// One place to change Quiet agents (the tile and Settings): drop the
// Running and Finished notifications when it goes on; reconnect when it
// goes off, so working agents get their running notification again.
fun setQuiet(c: Context, on: Boolean) {
    c.app.prefs.quiet = on
    if (on) Notifier.cancelQuiet(c) else AlertService.start(c, reconnect = true)
    runCatching { TileService.requestListeningState(c, ComponentName(c, QuietTile::class.java)) }
}

val NEEDS_YOU_URI: android.net.Uri = android.net.Uri.parse("herdrmonitor://home/needs")
fun isNeedsYouLink(uri: android.net.Uri?) = uri?.scheme == "herdrmonitor" && uri.host == "home" && uri.pathSegments.firstOrNull() == "needs"
