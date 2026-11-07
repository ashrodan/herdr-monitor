package io.github.ashrodan.herdrmonitor

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ashrodan.herdrmonitor.ui.AgentScreen
import io.github.ashrodan.herdrmonitor.ui.HerdrTheme
import io.github.ashrodan.herdrmonitor.ui.HomeScreen
import io.github.ashrodan.herdrmonitor.ui.SettingsScreen
import io.github.ashrodan.herdrmonitor.ui.SpaceScreen

sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data class SpaceR(val id: String) : Route
    data class Agent(val address: String) : Route
}

// The whole app is native Compose over the server's API: no WebView.
class MainActivity : ComponentActivity() {
    private val stack = mutableStateListOf<Route>(Route.Home)
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && !mayNotify()) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AlertService.start(this)
        setContent { HerdrTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() { super.onStart(); app.store.start() }
    override fun onStop() { super.onStop(); app.store.stop() }

    // A notification, the widget or herdrmonitor://agent/<addr>: open that agent.
    private fun handle(i: Intent?) {
        val addr = addressFrom(i?.data) ?: return
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        stack.add(Route.Agent(addr))
    }

    fun go(r: Route) { stack.add(r) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }

    @Composable
    private fun App() {
        val live by app.store.live.collectAsStateWithLifecycle()
        BackHandler(enabled = stack.size > 1) { back() }
        when (val r = stack.last()) {
            Route.Home -> HomeScreen(live, ::go)
            Route.Settings -> SettingsScreen(::back)
            is Route.SpaceR -> SpaceScreen(live, r.id, ::go, ::back)
            is Route.Agent -> AgentScreen(live, r.address, ::back)
        }
    }
}
