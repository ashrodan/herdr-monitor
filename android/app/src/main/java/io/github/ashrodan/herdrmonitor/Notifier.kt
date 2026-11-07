package io.github.ashrodan.herdrmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import org.json.JSONArray
import org.json.JSONObject

// The notifications: one per agent (tag = its address, so a new state
// replaces the old one), plus the quiet "connected" one of AlertService.
object Notifier {
    const val CH_ALERTS = "alerts"
    const val CH_SERVICE = "service"
    const val ID_AGENT = 1
    const val ID_SERVICE = 2
    const val GROUP = "herdr-agents"
    const val KEY_REPLY = "reply"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Agent alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "An agent needs you, is done, or hit an error"
        })
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while the app listens for alerts"
            setShowBadge(false)
        })
    }

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)
    private fun icon(c: Context) = Icon.createWithResource(c, R.drawable.ic_notification)

    // Request codes must differ per agent and action, or Android reuses the
    // first PendingIntent's extras.
    private fun code(address: String, what: String) = (address + "/" + what).hashCode()

    private fun openPi(c: Context, address: String?) = PendingIntent.getActivity(
        c, code(address ?: "", "open"), openAgentIntent(c, address), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun actionPi(c: Context, address: String, what: String, mutable: Boolean = false, extras: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(c, ActionReceiver::class.java).apply {
            action = "io.github.ashrodan.herdrmonitor.$what"
            putExtra(ActionReceiver.EXTRA_ADDRESS, address)
            extras()
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(c, code(address, what), i, flags)
    }

    // An action that types or presses keys: only on an unlocked phone.
    private fun locked(b: Notification.Action.Builder) = b.setAuthenticationRequired(true)

    private fun replyAction(c: Context, address: String, force: Boolean): Notification.Action {
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to the agent").build()
        val pi = actionPi(c, address, ActionReceiver.REPLY, mutable = true) { putExtra(ActionReceiver.EXTRA_FORCE, force) }
        return locked(Notification.Action.Builder(icon(c), "Reply", pi).addRemoteInput(input).setAllowGeneratedReplies(false)).build()
    }

    private fun interruptAction(c: Context, address: String) =
        locked(Notification.Action.Builder(icon(c), "Interrupt", actionPi(c, address, ActionReceiver.INTERRUPT))).build()

    private fun openAction(c: Context, address: String) = Notification.Action.Builder(icon(c), "Open", openPi(c, address)).build()

    private fun optionAction(c: Context, address: String, shape: List<QuestionShape>, j: Int, label: String): Notification.Action {
        val pi = actionPi(c, address, "${ActionReceiver.ANSWER}.$j") {
            putExtra(ActionReceiver.EXTRA_SHAPE, shapeJson(shape))
            putExtra(ActionReceiver.EXTRA_OPTION, j)
        }
        return locked(Notification.Action.Builder(icon(c), label.take(30), pi)).build()
    }

    // Android shows at most three action buttons:
    //  blocked at one single-choice question: its options (then Reply);
    //  blocked otherwise: Reply, Interrupt, Open;
    //  done or error: Reply, Open.
    fun actionsFor(c: Context, a: AgentInfo, state: String, ask: Part.Ask?): List<Notification.Action> {
        val opts = if (state == "blocked") ask?.quickOptions(3) else null
        if (opts != null) {
            val shape = ask!!.shape()
            val buttons = opts.mapIndexed { j, l -> optionAction(c, a.address, shape, j, l) }
            return if (buttons.size < 3) buttons + replyAction(c, a.address, force = true) else buttons
        }
        return when (state) {
            "blocked" -> listOf(replyAction(c, a.address, force = true), interruptAction(c, a.address), openAction(c, a.address))
            else -> listOf(replyAction(c, a.address, force = false), openAction(c, a.address))
        }
    }

    fun postAgent(c: Context, a: AgentInfo, state: String, ask: Part.Ask?, quiet: Boolean = false) {
        val question = if (state == "blocked") ask?.questions?.singleOrNull()?.question else null
        val body = question ?: alertBody(a, state)
        val n = Notification.Builder(c, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alertTitle(a.name, state))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openPi(c, a.address))
            .setAutoCancel(true)
            .setOnlyAlertOnce(quiet)
            .setGroup(GROUP)
            .setCategory(if (state == "blocked") Notification.CATEGORY_MESSAGE else Notification.CATEGORY_STATUS)
            .setColor(colorOf(state))
            .setWhen(a.changedMs ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setActions(*actionsFor(c, a, state, ask).toTypedArray())
            .build()
        nm(c).notify(a.address, ID_AGENT, n)
    }

    // After an action: say it failed, keep a way back into the app.
    fun postFailure(c: Context, address: String, what: String, error: String) {
        val n = Notification.Builder(c, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$what not sent")
            .setContentText(error)
            .setContentIntent(openPi(c, address))
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setOnlyAlertOnce(true)
            .build()
        nm(c).notify(address, ID_AGENT, n)
    }

    fun cancelAgent(c: Context, address: String) = nm(c).cancel(address, ID_AGENT)

    fun service(c: Context, text: String): Notification = Notification.Builder(c, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(text)
        .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true)
        .setShowWhen(false)
        .build()

    fun colorOf(state: String) = when (state) {
        "blocked" -> 0xFFF59E0B.toInt()
        "error" -> 0xFFEF4444.toInt()
        "done" -> 0xFF22C55E.toInt()
        "working" -> 0xFF3B82F6.toInt()
        else -> 0xFF9CA3AF.toInt()
    }

    fun shapeJson(shape: List<QuestionShape>): String = JSONArray().apply {
        shape.forEach { put(JSONObject().put("question", it.question).put("options", it.options).put("multi", it.multi)) }
    }.toString()

    fun shapeFrom(json: String?): List<QuestionShape> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> QuestionShape(o.getString("question"), o.getInt("options"), o.optBoolean("multi")) } }
    } catch (e: Exception) { emptyList() }
}
