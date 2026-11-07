package io.github.ashrodan.herdrmonitor

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var store: Store

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = Store { prefs.server }
        Notifier.channels(this)
    }
}

val Context.app get() = applicationContext as App

// herdrmonitor://agent/<addr>: opens the app on that agent.
fun agentUri(address: String): Uri = Uri.parse("herdrmonitor://agent/${pathPart(address)}")

fun openAgentIntent(context: Context, address: String?): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        if (address != null) data = agentUri(address)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

fun addressFrom(uri: Uri?): String? =
    if (uri?.scheme == "herdrmonitor" && uri.host == "agent") uri.pathSegments.firstOrNull() else null

// POST_NOTIFICATIONS is a runtime permission from Android 13; before that
// notifications are on unless the user turned them off.
fun Context.mayNotify(): Boolean = Build.VERSION.SDK_INT < 33 ||
    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
