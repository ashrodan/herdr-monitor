package io.github.ashrodan.herdrmonitor

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

// Notification actions -> the server. Android asks for an unlocked phone
// first (setAuthenticationRequired). The HTTP call runs off the main thread.
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return
        val what = intent.action?.substringAfterLast('.') ?: return
        val pending = goAsync()
        val c = context.applicationContext
        thread(name = "herdr-action") {
            val api = Api(c.app.prefs.server)
            val label = when {
                what == REPLY -> "Reply"
                what == INTERRUPT -> "Interrupt"
                else -> "Answer"
            }
            try {
                when {
                    what == REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
                        if (text.isNullOrEmpty()) throw IllegalArgumentException("empty reply")
                        api.send(address, text, intent.getBooleanExtra(EXTRA_FORCE, false))
                    }
                    what == INTERRUPT -> api.interrupt(address)
                    what.startsWith(ANSWER) || intent.hasExtra(EXTRA_OPTION) -> {
                        val shape = Notifier.shapeFrom(intent.getStringExtra(EXTRA_SHAPE))
                        api.answer(address, shape, 0, intent.getIntExtra(EXTRA_OPTION, 0))
                        // The Home card shows "Sent: <answer>" until the
                        // agent's next output, as after a tap in the app.
                        val l = intent.getStringExtra(EXTRA_LABEL) ?: "answer"
                        c.app.sent.value = c.app.sent.value + (address to SentMark(l, System.currentTimeMillis()))
                    }
                }
                // Done: the alert goes (the stream says what happens next).
                Notifier.cancelAgent(c, address)
            } catch (e: Exception) {
                Notifier.postFailure(c, address, label, e.message ?: e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_FORCE = "force"
        const val EXTRA_SHAPE = "shape"
        const val EXTRA_OPTION = "option"
        const val EXTRA_LABEL = "label"
        const val REPLY = "REPLY"
        const val INTERRUPT = "INTERRUPT"
        const val ANSWER = "ANSWER"
    }
}
