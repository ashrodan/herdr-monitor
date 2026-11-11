package io.github.ashrodan.herdrmonitor

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

// Notification actions -> the server the notification came from (not the
// one in use now, if the user switched). Android asks for an unlocked phone
// first (setAuthenticationRequired). The HTTP call runs off the main thread.
// A Reply that fails is kept in the outbox, so the text is not lost.
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return
        val what = intent.action?.substringAfterLast('.') ?: return
        val pending = goAsync()
        val c = context.applicationContext
        thread(name = "herdr-action") {
            val server = intent.getStringExtra(EXTRA_SERVER) ?: c.app.prefs.server
            val api = Api(server)
            var reply: String? = null
            val force = intent.getBooleanExtra(EXTRA_FORCE, false)
            val label = when {
                what == REPLY -> "Reply"
                what == INTERRUPT -> "Stop"
                else -> "Answer"
            }
            try {
                when {
                    what == REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
                        if (text.isNullOrEmpty()) throw IllegalArgumentException("empty reply")
                        reply = text
                        api.send(address, text, force)
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
                val why = e.message ?: e.javaClass.simpleName
                val text = reply
                if (text != null) {
                    // Retry: it goes later. Refused or unconfirmed: it waits
                    // in the app for Send again or Delete.
                    val f = failureOf(e)
                    c.app.outbox.add(server, address, text, force, why, stateAfter(f))
                    val where = if (f == Failure.Retry) "It goes when the link is back." else "Open the app to send it again or delete it."
                    Notifier.postFailure(c, address, replyFailureTitle(f), "$why. $where")
                } else {
                    Notifier.postFailure(c, address, "$label not sent", why)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_SERVER = "server"
        const val EXTRA_FORCE = "force"
        const val EXTRA_SHAPE = "shape"
        const val EXTRA_OPTION = "option"
        const val EXTRA_LABEL = "label"
        const val REPLY = "REPLY"
        const val INTERRUPT = "INTERRUPT"
        const val ANSWER = "ANSWER"
    }
}
