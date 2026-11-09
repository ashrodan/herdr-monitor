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

// The notifications, on three channels:
//  - Agent summary (low, silent): ONE ongoing notification, which is also
//    the foreground service's: the link state, the counts, and up to five
//    agents (Needs you, Failed, Working, unseen Done). Working and Done
//    have no notification of their own.
//  - Needs you (high): one per agent that is blocked or failed (tag = its
//    address, so a new state replaces the old one).
//  - Alert health (default): only when alerts cannot work at all.
// The summary is the group summary of the server's group, and each Needs
// you notification is a child, so Android does not add a group of its own.
object Notifier {
    const val CH_NEEDS = "needs_you"
    // The stored id stays "running", so a user's setting for it survives.
    const val CH_SUMMARY = "running"
    const val CH_HEALTH = "alert_health"
    // Gone: the Finished and Connection channels (and the first "alerts").
    private val OLD_CHANNELS = listOf("alerts", "finished", "service")
    const val ID_AGENT = 1
    // The summary has the foreground service's id.
    const val ID_SUMMARY = 2
    const val ID_HEALTH = 5
    const val ID_TEST = 7
    // Ids of notifications that no longer exist: running (3), group
    // summary (4), overview (6).
    val LEGACY_IDS = setOf(3, 4, 6)
    const val TAG_HEALTH = "health"
    const val TAG_TEST = "test"
    const val KEY_REPLY = "reply"

    fun channels(c: Context) {
        val nm = nm(c)
        // An update from an older build: its notifications go first, then
        // their channels.
        for (n in nm.activeNotifications) {
            if (n.id in LEGACY_IDS || (n.id == ID_AGENT && n.notification.channelId == "finished")) nm.cancel(n.tag, n.id)
        }
        OLD_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
        nm.createNotificationChannel(NotificationChannel(CH_NEEDS, "Needs you", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "An agent asks a question or stopped with an error"
        })
        // Same id as before: this renames it and keeps the user's importance.
        nm.createNotificationChannel(NotificationChannel(CH_SUMMARY, "Agent summary", NotificationManager.IMPORTANCE_LOW).apply {
            description = "One silent summary of your agents while background alerts are on"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_HEALTH, "Alert health", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Agent alerts cannot work: an old server, a refused link, or a long outage"
        })
    }

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)
    // One group per host.
    fun group(c: Context) = "host:" + hostOf(c.app.prefs.server)
    private fun icon(c: Context) = Icon.createWithResource(c, R.drawable.ic_stat_herdr)

    // Request codes must differ per agent and action, or Android reuses the
    // first PendingIntent's extras.
    private fun code(address: String, what: String) = (address + "/" + what).hashCode()

    private fun openPi(c: Context, address: String?) = PendingIntent.getActivity(
        c, code(address ?: "", "open"), openAgentIntent(c, address), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun actionPi(c: Context, address: String, what: String, mutable: Boolean = false, extras: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(c, ActionReceiver::class.java).apply {
            action = "io.github.ashrodan.herdrmonitor.$what"
            putExtra(ActionReceiver.EXTRA_ADDRESS, address)
            putExtra(ActionReceiver.EXTRA_SERVER, c.app.prefs.server)
            extras()
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(c, code(address, what), i, flags)
    }

    // An action that types or presses keys: only on an unlocked phone.
    private fun locked(b: Notification.Action.Builder) = b.setAuthenticationRequired(true)

    // The action runs in the background: no activity opens. (The platform
    // builder has no setter; NotificationCompat reads this extra, and so do
    // wearables and Android Auto.)
    private fun background(b: Notification.Action.Builder) =
        b.addExtras(android.os.Bundle().apply { putBoolean(EXTRA_SHOWS_UI, false) })

    private const val EXTRA_SHOWS_UI = "android.support.action.showsUserInterface"

    private fun replyAction(c: Context, address: String, force: Boolean): Notification.Action {
        val input = RemoteInput.Builder(KEY_REPLY).setLabel("Reply to the agent").build()
        val pi = actionPi(c, address, ActionReceiver.REPLY, mutable = true) { putExtra(ActionReceiver.EXTRA_FORCE, force) }
        return locked(background(Notification.Action.Builder(icon(c), "Reply", pi).addRemoteInput(input).setAllowGeneratedReplies(false)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY))).build()
    }

    private fun openAction(c: Context, address: String) = Notification.Action.Builder(icon(c), "Open", openPi(c, address)).build()

    private fun stopAction(c: Context, address: String) =
        locked(background(Notification.Action.Builder(icon(c), "Stop", actionPi(c, address, ActionReceiver.INTERRUPT)))).build()

    // An answer button: the receiver needs the form's shape, the option and
    // its label (to mark the card "Sent" in the app).
    fun answerIntent(c: Context, address: String, shape: List<QuestionShape>, j: Int, label: String) = Intent(c, ActionReceiver::class.java).apply {
        action = "io.github.ashrodan.herdrmonitor.${ActionReceiver.ANSWER}.$j"
        putExtra(ActionReceiver.EXTRA_ADDRESS, address)
        putExtra(ActionReceiver.EXTRA_SERVER, c.app.prefs.server)
        putExtra(ActionReceiver.EXTRA_SHAPE, shapeJson(shape))
        putExtra(ActionReceiver.EXTRA_OPTION, j)
        putExtra(ActionReceiver.EXTRA_LABEL, label)
    }

    private fun optionAction(c: Context, address: String, shape: List<QuestionShape>, j: Int, label: String): Notification.Action {
        val pi = PendingIntent.getBroadcast(c, code(address, "${ActionReceiver.ANSWER}.$j"), answerIntent(c, address, shape, j, label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return locked(background(Notification.Action.Builder(icon(c), label.take(30), pi))).build()
    }

    // Android shows at most three action buttons, and Reply is always one
    // (actionPlan): two answers at most, so a question with three or more
    // options can still get a typed reply.
    fun actionsFor(c: Context, a: AgentInfo, state: String, ask: Part.Ask?): List<Notification.Action> {
        val quick = if (state == "blocked") ask?.quickOptions(MAX_QUICK) else null
        val shape = ask?.shape().orEmpty()
        return actionPlan(state, quick).map { act ->
            when (act) {
                is Act.Answer -> optionAction(c, a.address, shape, act.index, act.label)
                Act.Reply -> replyAction(c, a.address, force = state == "blocked")
                Act.Open -> openAction(c, a.address)
                Act.Stop -> stopAction(c, a.address)
            }
        }
    }

    private fun host(c: Context) = shortHost(c.app.prefs.server)

    fun postAgent(c: Context, a: AgentInfo, state: String, ask: Part.Ask?, quiet: Boolean = false) {
        val question = if (state == "blocked") ask?.questions?.singleOrNull()?.question else null
        val body = question ?: alertBody(a, state)
        val n = Notification.Builder(c, CH_NEEDS)
            .setSmallIcon(R.drawable.ic_stat_herdr)
            .setContentTitle(alertTitle(a.name, state))
            .setContentText(body)
            .setSubText(subtextOf(a, host(c)))
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openPi(c, a.address))
            .setAutoCancel(true)
            .setOnlyAlertOnce(quiet)
            .setGroup(group(c))
            .setCategory(if (state == "blocked") Notification.CATEGORY_MESSAGE else Notification.CATEGORY_ERROR)
            .setColor(colorOf(state))
            .setWhen(a.changedMs ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setActions(*actionsFor(c, a, state, ask).toTypedArray())
            .build()
        nm(c).notify(a.address, ID_AGENT, n)
    }

    // Another server: its agents' alerts go (the next snapshot posts the
    // new server's). The summary stays; the service updates it.
    fun cancelAllAgents(c: Context) {
        for (n in nm(c).activeNotifications) if (n.id == ID_AGENT) nm(c).cancel(n.tag, n.id)
    }

    // After an action: say it failed, keep a way back into the app. title:
    // "Reply queued", "Reply kept in app", or "<what> not sent".
    fun postFailure(c: Context, address: String, title: String, error: String) {
        val n = Notification.Builder(c, CH_NEEDS)
            .setSmallIcon(R.drawable.ic_stat_herdr)
            .setContentTitle(title)
            .setContentText(error)
            .setStyle(Notification.BigTextStyle().bigText(error))
            .setContentIntent(openPi(c, address))
            .setActions(openAction(c, address))
            .setAutoCancel(true)
            .setGroup(group(c))
            .setOnlyAlertOnce(true)
            .build()
        nm(c).notify(address, ID_AGENT, n)
    }

    fun cancelAgent(c: Context, address: String) = nm(c).cancel(address, ID_AGENT)

    // The agent summary. Silent and ongoing; the group summary of this
    // server's group (children alert, it does not). Tap: Home.
    fun summary(c: Context, v: SummaryView): Notification {
        val style = Notification.InboxStyle().setBigContentTitle(v.title).setSummaryText(v.sub)
        v.lines.forEach { style.addLine(it) }
        return Notification.Builder(c, CH_SUMMARY)
            .setSmallIcon(R.drawable.ic_stat_herdr)
            .setContentTitle(v.title)
            .setContentText(v.text)
            .setSubText(v.sub)
            .setStyle(style)
            .setColor(c.getColor(R.color.herdr_notification_accent))
            .setGroup(group(c))
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(PendingIntent.getActivity(c, code("summary", "open"), Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    fun postSummary(c: Context, v: SummaryView) = nm(c).notify(ID_SUMMARY, summary(c, v))

    private fun alertsPi(c: Context, what: String) =
        PendingIntent.getActivity(c, code("alerts", what), alertsIntent(c), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // The health alert: one per cause (the caller dedupes with its key).
    // Not in the server's group: it is about the link, not an agent.
    fun postHealth(c: Context, a: HealthAlert) {
        val retry = PendingIntent.getService(c, code("health", "retry"),
            Intent(c, AlertService::class.java).setAction(AlertService.ACTION_RECONNECT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CH_HEALTH)
            .setSmallIcon(R.drawable.ic_stat_herdr)
            .setContentTitle(a.title)
            .setContentText(a.text)
            .setStyle(Notification.BigTextStyle().bigText(a.text))
            .setColor(colorOf("error"))
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentIntent(alertsPi(c, "health"))
            .setOnlyAlertOnce(true)
            .setGroup("health")
            .setActions(
                Notification.Action.Builder(icon(c), "Open alert setup", alertsPi(c, "setup")).build(),
                Notification.Action.Builder(icon(c), "Retry", retry).build(),
            )
            .build()
        nm(c).notify(TAG_HEALTH, ID_HEALTH, n)
    }

    fun cancelHealth(c: Context) = nm(c).cancel(TAG_HEALTH, ID_HEALTH)

    // A local test: checks permission, channel and style, in the same group
    // as a real Needs you alert. It names no agent and has no action that
    // types, presses keys or stops anything.
    fun postTest(c: Context) {
        val text = "This is a test from the Alerts screen. No agent was contacted."
        val n = Notification.Builder(c, CH_NEEDS)
            .setSmallIcon(R.drawable.ic_stat_herdr)
            .setContentTitle("Test alert · needs you")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSubText(host(c))
            .setColor(colorOf("blocked"))
            .setContentIntent(alertsPi(c, "test"))
            .setAutoCancel(true)
            .setGroup(group(c))
            .build()
        nm(c).notify(TAG_TEST, ID_TEST, n)
    }

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
