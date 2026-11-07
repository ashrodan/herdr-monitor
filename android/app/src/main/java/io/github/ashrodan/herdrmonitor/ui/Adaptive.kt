package io.github.ashrodan.herdrmonitor.ui

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import io.github.ashrodan.herdrmonitor.Live
import io.github.ashrodan.herdrmonitor.Route
import kotlinx.coroutines.launch

// Bottom bar on a compact width, a rail on medium, a permanent drawer on
// expanded (the brief's Platform shell).
@Composable
fun navSuiteType(): NavigationSuiteType {
    val w = currentWindowAdaptiveInfo().windowSizeClass
    return when {
        w.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> NavigationSuiteType.NavigationDrawer
        w.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> NavigationSuiteType.NavigationRail
        else -> NavigationSuiteType.NavigationBar
    }
}

// Sessions as list and detail: on a medium or expanded width (a foldable,
// a tablet, a phone in landscape) the list and the open session sit side
// by side. On one pane, opening a session goes to its own screen as before,
// and back returns to the list.
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HomeListDetail(live: Live, go: (Route) -> Unit) {
    val nav = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val twoPane = nav.scaffoldDirective.maxHorizontalPartitions > 1
    BackHandler(nav.canNavigateBack()) { scope.launch { nav.navigateBack() } }
    ListDetailPaneScaffold(
        directive = nav.scaffoldDirective,
        value = nav.scaffoldValue,
        listPane = {
            AnimatedPane {
                HomeScreen(live, go = { r ->
                    if (twoPane && r is Route.Agent) scope.launch { nav.navigateTo(ListDetailPaneScaffoldRole.Detail, "${r.terminal}|${r.address}") }
                    else go(r)
                }, selected = nav.currentDestination?.contentKey?.substringAfter('|')?.takeIf { twoPane })
            }
        },
        detailPane = {
            AnimatedPane {
                val k = nav.currentDestination?.contentKey
                if (k == null) EmptyDetail()
                else key(k) {
                    AgentScreen(live, k.substringAfter('|'), back = { scope.launch { nav.navigateBack() } },
                        showTerminalOnOpen = k.substringBefore('|').toBooleanStrictOrNull() ?: false,
                        showBack = nav.scaffoldValue[ListDetailPaneScaffoldRole.List] != PaneAdaptedValue.Expanded)
                }
            }
        },
    )
}

@Composable
private fun EmptyDetail() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("Pick a session.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Remove animations is on (animator duration scale 0): no morphing,
// shimmer or shape animation; state changes still show at once.
@Composable
@ReadOnlyComposable
fun reducedMotion(): Boolean =
    Settings.Global.getFloat(LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
