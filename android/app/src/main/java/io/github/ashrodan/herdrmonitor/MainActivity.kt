package io.github.ashrodan.herdrmonitor

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.ashrodan.herdrmonitor.ui.AgentScreen
import io.github.ashrodan.herdrmonitor.ui.HomeListDetail
import io.github.ashrodan.herdrmonitor.ui.SettingsScreen
import io.github.ashrodan.herdrmonitor.ui.SpaceScreen
import io.github.ashrodan.herdrmonitor.ui.theme.HerdrTheme
import io.github.ashrodan.herdrmonitor.ui.theme.isDark

sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data class SpaceR(val id: String) : Route
    data class Agent(val address: String, val terminal: Boolean = false) : Route
}

// Navigation Compose routes. Addresses and space ids hold "/", so they are
// encoded into one path segment.
object Nav {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SPACE = "space/{id}"
    const val AGENT = "agent/{address}?terminal={terminal}"
    fun path(r: Route): String = when (r) {
        Route.Home -> HOME
        Route.Settings -> SETTINGS
        is Route.SpaceR -> "space/${Uri.encode(r.id)}"
        is Route.Agent -> "agent/${Uri.encode(r.address)}?terminal=${r.terminal}"
    }
}

// The whole app is native Compose over the server's API: no WebView.
// Back (and the predictive back gesture) comes from Navigation Compose;
// sheets close on back before the screen pops.
class MainActivity : ComponentActivity() {
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // An agent to open, from a notification, the widget or a deep link.
    private var pending by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) { pending = addressFrom(intent?.data); needsYou(intent?.data) }
        // Ask the first time, and again only while Android still lets the
        // app ask (one denial). After "Don't allow" twice, asking only opens
        // and shuts the system screen on every start, which slows it.
        if (Build.VERSION.SDK_INT >= 33 && !mayNotify() &&
            (!app.prefs.askedNotify || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            app.prefs.askedNotify = true
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AlertService.start(this)
        setContent {
            val look by app.look.collectAsStateWithLifecycle()
            val dark = isDark(look.pref, isSystemInDarkTheme())
            // Light or dark status and navigation bar icons, to match the theme.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            HerdrTheme(look.pref, look.wallpaperColours) { Screens() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        addressFrom(intent.data)?.let { pending = it }
        needsYou(intent.data)
    }

    // The Herdr tile: Sessions, filtered to Needs you.
    private var toHome by mutableStateOf(false)
    private fun needsYou(uri: android.net.Uri?) {
        if (!isNeedsYouLink(uri)) return
        app.homeFilter.value = Bucket.NeedsYou
        toHome = true
    }

    override fun onStart() { super.onStart(); app.store.start() }
    override fun onStop() { super.onStop(); app.store.stop() }

    @Composable
    private fun Screens() {
        val nav = rememberNavController()
        // The NavHost builder must not capture values that change (like the
        // live state): a new builder makes a new graph, and NavHost then
        // resets the back stack to Home. Each screen reads the live state.
        val go: (Route) -> Unit = remember(nav) { { r -> nav.navigate(Nav.path(r)) { launchSingleTop = true } } }
        val back: () -> Unit = remember(nav) { { if (!nav.popBackStack()) finish() } }
        // On a cold start, wait until NavHost has put Home on the back stack;
        // a navigate before that is lost when NavHost starts its graph.
        LaunchedEffect(nav) {
            nav.currentBackStackEntryFlow.first()
            snapshotFlow { pending }.filterNotNull().collect { addr ->
                openAgent(nav, addr)
                pending = null
            }
        }
        LaunchedEffect(nav, toHome) {
            if (!toHome) return@LaunchedEffect
            nav.currentBackStackEntryFlow.first()
            nav.popBackStack(Nav.HOME, inclusive = false)
            toHome = false
        }
        NavHost(nav, startDestination = Nav.HOME) {
            composable(Nav.HOME) { HomeListDetail(live(), go) }
            composable(Nav.SETTINGS) { SettingsScreen(back) }
            composable(Nav.SPACE) { e -> SpaceScreen(live(), e.arguments?.getString("id").orEmpty(), go, back) }
            composable(Nav.AGENT, arguments = listOf(navArgument("terminal") { type = NavType.BoolType; defaultValue = false })) { e ->
                AgentScreen(live(), e.arguments?.getString("address").orEmpty(), back,
                    showTerminalOnOpen = e.arguments?.getBoolean("terminal") ?: false)
            }
        }
    }

    @Composable
    private fun live(): Live = app.store.live.collectAsStateWithLifecycle().value

    // Home, then that agent: back from the agent returns to Home.
    private fun openAgent(nav: NavHostController, address: String) {
        nav.navigate(Nav.path(Route.Agent(address))) {
            popUpTo(Nav.HOME)
            launchSingleTop = true
        }
    }
}
