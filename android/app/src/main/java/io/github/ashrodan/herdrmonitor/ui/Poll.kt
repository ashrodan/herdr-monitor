package io.github.ashrodan.herdrmonitor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope

// A LaunchedEffect that runs only while the app is on screen (STARTED).
// A plain LaunchedEffect keeps running when the app goes to the background:
// a stopped activity keeps its composition. The alert service keeps the
// process alive all day, so a polling loop there would read the server
// until Android kills the process. This one stops at onStop and starts
// again (from the top) at onStart.
@Composable
fun PollWhileStarted(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, *keys) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED, block) }
}
