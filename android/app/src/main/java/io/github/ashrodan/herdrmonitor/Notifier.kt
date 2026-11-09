package io.github.ashrodan.herdrmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

// The notifications, on four channels (Needs you, Running, Finished, Alert
// health): one alert per agent (tag = its address, so a new state replaces
// the old one), one silent overview of all active agents, a running
// notification per pinned working agent (at most 3), a summary per host so
// the agent ones group, one health alert when the alert link cannot work,
// and the quiet "connected" one of AlertService.
object Notifier {
    const val CH_NEEDS = "needs_you"
    const val CH_RUNNING = "running"
    const val CH_FINISHED = "finished"
    const val CH_SERVICE = "service"
    const val CH_HEALTH = "alert_health"
    private const val CH_OLD = "alerts"
    const val ID_AGENT = 1
    const val ID_SERVICE = 2
    const val ID_RUNNING = 3
    const val ID_SUMMARY = 4
    const val ID_HEALTH = 5
    const val ID_OVERVIEW = 6
    const val ID_TEST = 7
    const val TAG_OVERVIEW = "overview"
    const val TAG_HEALTH = "health"
    const val TAG_TEST = "test"
    const val KEY_REPLY = "reply"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(CH_OLD)
        nm.createNotificationChannel(NotificationChannel(CH_NEEDS, "Needs you", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "An agent asks a question or stopped with an error"
        })
        nm.createNotificationChannel(NotificationChannel(CH_RUNNING, "Running", NotificationManager.IMPORTANCE_LOW).apply {
            description = "What a working agent does now. Silent."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_FINISHED, "Finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "An agent finished its turn"
        })
        nm.createNotificationChannel(NotificationChannel(CH_HEALTH, "Alert health", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Agent alerts cannot work: an old server, a refused link, or a long outage"
        })
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while the app listens for alerts"
            setShowBadge(false)
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

    private fun prAction(c: Context, address: String, url: String) = Notification.Action.Builder(icon(c), "Open PR",
        PendingIntent.getActivity(c, code(address, "pr"), Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()

    private fun viewAction(c: Context, address: String) = Notification.Action.Builder(icon(c), "View session", openPi(c, address)).build()

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
    fun actionsFor(c: Context, a: AgentInfo, state: String, ask: Part.Ask?, pr: Pr? = null): List<Notification.Action> {
        val quick = if (state == "blocked") ask?.quickOptions(MAX_QUICK) else null
        val shape = ask?.shape().orEmpty()
        return actionPlan(state, quick, pr != null).map { act ->
            when (act) {
                is Act.Answer -> optionAction(c, a.address, shape, act.index, act.label)
                Act.Reply -> replyAction(c, a.address, force = state == "blocked")
                Act.Open -> openAction(c, a.address)
                Act.Stop -> stopAction(c, a.address)
                Act.OpenPr -> prAction(c, a.address, pr!!.url)
                Act.View -> viewAction(c, a.address)
            }
        }
    }

    private fun host(c: Context) = shortHost(c.app.prefs.server)

    fun postAgent(c: Context, a: AgentInfo, state: String, ask: Part.Ask?, quiet: Boolean = false, pr: Pr? = null) {
        val question = if (state == "blocked") ask?.questions?.singleOrNull()?.question else null
        val body = question ?: if (state == "blocked") alertBody(a, state) else finishedLine(a, state, pr)
        val ch = if (channelFor(state) == Channel.Finished) CH_FINISHED else CH_NEEDS
        val n = Notification.Builder(c, ch)
            .setSmallIcon(R.drawable.ic_stat_herdr)
            .setContentTitle(alertTitle(a.name, state))
            .setContentText(body)
            .setSubText(subtextOf(a, host(c)))
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openPi(c, a.address))
            .setAutoCancel(true)
            .setOnlyAlertOnce(quiet)
            .setGroup(group(c))
            .setCategory(if (state == "blocked") Notification.CATEGORY_MESSAGE else Notification.CATEGORY_STATUS)
            .setColor(colorOf(state))
            .setWhen(a.changedMs ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setActions(*actionsFor(c, a, state, ask, pr).toTypedArray())
            .build()
        nm(c).notify(a.address, ID_AGENT, n)
        summary(c)
    }

    // A working agent: its name, the current step, Stop, and the stage
    // (Plan, Edit, Verify, PR). Segmented ProgressStyle on Android 16
    // (API 36); a plain progress bar below. Silent.
    // What each running notification and each host summary last showed:
    // post again only when it changes, so a slow device is not flooded.
    private val lastRunning = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lastSummary = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun postRunning(c: Context, a: AgentInfo, step: String?, stage: Int?) {
        val text = step ?: a.msg?.takeIf { it.isNotBlank() } ?: "Working"
        val sig = listOf(a.name, text, stage?.toString() ?: "-", a.changedMs?.toString() ?: "-").joinToString("\u0000")
        if (lastRunning.put(a.address, sig) == sig) return
        val b = Notification.Builder(c, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_herdr)
            .setContentTitle(a.name)
            .setContentText(text)
            .setSubText(listOfNotNull(stage?.let { STAGES[it] }, subtextOf(a, null)).joinToString(" · ").ifEmpty { null })
            .setContentIntent(openPi(c, a.address))
            .setOnlyAlertOnce(true)
            .setGroup(group(c))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setColor(colorOf("working"))
            .setWhen(a.changedMs ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setUsesChronometer(a.changedMs != null)
            .setActions(stopAction(c, a.address))
        if (stage != null) {
            if (Build.VERSION.SDK_INT >= 36) {
                b.setStyle(Notification.ProgressStyle()
                    .setStyledByProgress(true)
                    .setProgressSegments(STAGES.map { Notification.ProgressStyle.Segment(1).setColor(colorOf("working")) })
                    .setProgress(stage + 1))
            } else b.setProgress(STAGES.size, stage + 1, false)
        } else b.setProgress(0, 0, true)
        nm(c).notify(a.address, ID_RUNNING, b.build())
        summary(c)
    }

    fun cancelRunning(c: Context, address: String) {
        // Always cancel: one may be left from before an app restart.
        nm(c).cancel(address, ID_RUNNING)
        if (lastRunning.remove(address) != null) summary(c)
    }

    // Another server: its agents' alerts, running notifications and group
    // summaries go (the next snapshot posts the new server's). The service
    // notification stays.
    fun cancelAllAgents(c: Context) {
        for (n in nm(c).activeNotifications) if (n.id == ID_AGENT || n.id == ID_RUNNING || n.id == ID_SUMMARY || n.id == ID_OVERVIEW) nm(c).cancel(n.tag, n.id)
        lastRunning.clear()
        lastSummary.clear()
        lastOverview = null
    }

    // Quiet agents turned on: only Needs you stays.
    fun cancelQuiet(c: Context) {
        for (n in nm(c).activeNotifications) {
            val ch = n.notification.channelId
            if (ch == CH_RUNNING || ch == CH_FINISHED) if (n.tag != null) { nm(c).cancel(n.tag, n.id); lastRunning.remove(n.tag) }
        }
        lastOverview = null
        summary(c)
    }

    // The group summary for this host: posted while the group has a child,
    // removed with the last one. Silent: the children alert.
    fun summary(c: Context) {
        val g = group(c)
        val kids = nm(c).activeNotifications.filter { it.notification.group == g && it.id != ID_SUMMARY && it.id != ID_SERVICE && it.id != ID_OVERVIEW }
        if (kids.isEmpty()) {
            // Always cancel: one may be left from before an app restart.
            // The dedupe below is for posting only.
            lastSummary.remove(g)
            nm(c).cancel(g, ID_SUMMARY)
            return
        }
        val lines = kids.mapNotNull { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) }
        val sig = lines.joinToString("\u0000")
        if (lastSummary.put(g, sig) == sig) return
        val style = Notification.InboxStyle().setSummaryText(hostOf(c.app.prefs.server))
        lines.take(5).forEach { style.addLine(it) }
        val n = Notification.Builder(c, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_herdr)
            .setContentTitle("${kids.size} agent${if (kids.size == 1) "" else "s"}")
            .setColor(c.getColor(R.color.herdr_notification_accent))
            .setStyle(style)
            .setGroup(g)
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setContentIntent(PendingIntent.getActivity(c, code(g, "summary"), Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        nm(c).notify(g, ID_SUMMARY, n)
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

    // The overview: every active agent of this server in one silent
    // notification (blocked, error, working). Standalone, in a group of its
    // own, so it does not depend on how a phone shows group summaries.
    @Volatile private var lastOverview: String? = null

    fun postOverview(c: Context, ov: Overview?) {
        if (ov == null) {
            if (lastOverview != null || nm(c).activeNotifications.any { it.id == ID_OVERVIEW }) nm(c).cancel(TAG_OVERVIEW, ID_OVERVIEW)
            lastOverview = null
            return
        }
        if (lastOverview == ov.sig) return
        lastOverview = ov.sig
        val style = Notification.InboxStyle().setBigContentTitle(ov.title).setSummaryText(host(c))
        ov.lines.forEach { style.addLine(it) }
        if (ov.more > 0) style.addLine("+${ov.more} more")
        val n = Notification.Builder(c, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_herdr)
            .setContentTitle(ov.title)
            .setContentText(ov.lines.firstOrNull())
            .setStyle(style)
            .setColor(c.getColor(R.color.herdr_notification_accent))
            .setGroup("overview")
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(PendingIntent.getActivity(c, code("overview", "open"), Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        nm(c).notify(TAG_OVERVIEW, ID_OVERVIEW, n)
    }

    private fun alertsPi(c: Context, what: String) =
        PendingIntent.getActivity(c, code("alerts", what), alertsIntent(c), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // The health alert: one per cause (the caller dedupes with its key).
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

    // A local test: checks permission, channel and style. It names no agent
    // and has no action that types, presses keys or stops anything.
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
            .setGroup("test")
            .build()
        nm(c).notify(TAG_TEST, ID_TEST, n)
    }

    fun cancelAgent(c: Context, address: String) { nm(c).cancel(address, ID_AGENT); summary(c) }

    fun service(c: Context, text: String): Notification = Notification.Builder(c, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_herdr)
        .setContentTitle(text)
        .setColor(c.getColor(R.color.herdr_notification_accent))
        .setContentIntent(alertsPi(c, "service"))
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
