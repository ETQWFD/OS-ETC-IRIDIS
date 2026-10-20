package com.iris.localai.agent

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 1.14.0: work the background asks of a running IRIS. The chat screen registers [runner] while it
 * exists; when it returns false (no screen, busy, no model) the caller falls back to a notification.
 */
object AgentBus {
    enum class Kind { RESUME, SCHEDULED }
    data class Job(
        val kind: Kind,
        val prompt: String,
        val title: String = "",
        /** RESUME: the conversation whose plan continues. */
        val conversationId: String? = null,
        /** Heartbeat check number (RESUME), so the next check is counted on. */
        val attempt: Int = 0,
        /** Stop the run after this many minutes (0 = no limit). */
        val maxMinutes: Int = 0,
    )
    @Volatile var runner: ((Job) -> Boolean)? = null
    fun offer(job: Job): Boolean = runCatching { runner?.invoke(job) == true }.getOrDefault(false)
}

/** Battery/charging rules for background runs. Pure parts are unit-tested. */
object PowerGate {
    const val MIN_BATTERY = 20
    fun allows(batteryPct: Int, charging: Boolean, chargingOnly: Boolean): Boolean =
        if (chargingOnly) charging else charging || batteryPct >= MIN_BATTERY

    fun read(ctx: Context): Pair<Int, Boolean> {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        val charging = bm?.isCharging ?: false
        return pct to charging
    }

    fun check(ctx: Context, chargingOnly: Boolean): Boolean = read(ctx).let { (p, c) -> allows(p, c, chargingOnly) }
}

/** Pure heartbeat rules (unit-tested). */
object HeartbeatRules {
    /** Minutes until check number [attempt] (0 = right after the run). */
    fun delayMinutes(attempt: Int, firstMin: Int, everyMin: Int): Int = if (attempt <= 0) firstMin else everyMin

    sealed interface Verdict {
        data object Nothing : Verdict
        data class Pending(val left: Int, val next: String) : Verdict
    }

    /** Zero-LLM check: is there unfinished work in this conversation's plan? */
    fun check(plan: Plan?): Verdict {
        if (plan == null || plan.steps.isEmpty() || plan.finished) return Verdict.Nothing
        val i = plan.current.coerceAtLeast(0)
        return Verdict.Pending(plan.steps.size - plan.done, plan.steps[i].text)
    }

    const val RESUME_PROMPT = "（心跳检查）继续执行计划里还没完成的步骤；全部完成后简短汇报结果。"
}

/**
 * 1.14.0 心跳: after an agent run ends (done or stopped) IRIS looks at its plan after a few
 * minutes, then every hour, without loading the model. Only unfinished steps wake the model
 * (when IRIS is still running and the battery allows it); otherwise a notification asks.
 */
object Heartbeat {
    const val ACTION = "com.iris.localai.HEARTBEAT"
    const val EXTRA_CONV = "hb_conv"
    const val EXTRA_ATTEMPT = "hb_attempt"
    const val EXTRA_EVERY = "hb_every"
    const val EXTRA_MAX = "hb_max"
    const val EXTRA_AUTO = "hb_auto"
    /** Notification tap: continue this conversation's plan. */
    const val EXTRA_RESUME = "iris_resume_conv"
    private const val CHANNEL = "iris_heartbeat"
    private const val PREFS = "iris_plans"

    @Volatile private var store: PlanStore? = null

    fun plans(ctx: Context): PlanStore = store ?: synchronized(this) {
        store ?: run {
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            PlanStore({ p.getString("plans", null) }, { p.edit().putString("plans", it).apply() }).also { store = it }
        }
    }

    private fun pi(ctx: Context, convId: String, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, ("hb-$convId").hashCode(),
            Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION).putExtra(EXTRA_CONV, convId).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Arms check [attempt] of [convId] per [s]; does nothing when the heartbeat is off or used up. */
    fun arm(ctx: Context, convId: String, attempt: Int, s: AgentSettings) {
        if (!s.heartbeat || attempt >= s.heartbeatMaxChecks) { cancel(ctx, convId); return }
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + HeartbeatRules.delayMinutes(attempt, s.heartbeatFirstMin, s.heartbeatEveryMin) * 60_000L
        val p = pi(ctx, convId) {
            putExtra(EXTRA_ATTEMPT, attempt); putExtra(EXTRA_EVERY, s.heartbeatEveryMin)
            putExtra(EXTRA_MAX, s.heartbeatMaxChecks); putExtra(EXTRA_AUTO, s.heartbeatAutoResume)
        }
        // Inexact on purpose: Android batches it with other wake-ups (cheaper than an exact alarm).
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
        pending[convId] = at
        android.util.Log.i("IrisAgent", "heartbeat armed conv=$convId attempt=$attempt in ${(at - System.currentTimeMillis()) / 60000}min")
    }

    fun cancel(ctx: Context, convId: String) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pi(ctx, convId))
        pending.remove(convId)
    }

    /** Conversation id → next check (this process only; for the status rack). */
    val pending = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun onFire(ctx: Context, intent: Intent) {
        val conv = intent.getStringExtra(EXTRA_CONV) ?: return
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
        pending.remove(conv)
        when (val v = HeartbeatRules.check(plans(ctx).get(conv))) {
            HeartbeatRules.Verdict.Nothing -> android.util.Log.i("IrisAgent", "heartbeat conv=$conv: nothing to do")
            is HeartbeatRules.Verdict.Pending -> {
                val auto = intent.getBooleanExtra(EXTRA_AUTO, true)
                val ran = auto && PowerGate.check(ctx, chargingOnly = false) &&
                    AgentBus.offer(AgentBus.Job(AgentBus.Kind.RESUME, HeartbeatRules.RESUME_PROMPT, conversationId = conv, attempt = attempt, maxMinutes = 10))
                android.util.Log.i("IrisAgent", "heartbeat conv=$conv: ${v.left} steps left, resumed=$ran")
                if (!ran) {
                    notify(ctx, conv, v)
                    // Not resumed: keep checking at the long interval.
                    val every = intent.getIntExtra(EXTRA_EVERY, 60)
                    val max = intent.getIntExtra(EXTRA_MAX, 6)
                    if (attempt + 1 < max) arm(ctx, conv, attempt + 1, AgentSettings(heartbeatEveryMin = every, heartbeatMaxChecks = max, heartbeatAutoResume = auto))
                }
            }
        }
    }

    private fun notify(ctx: Context, conv: String, v: HeartbeatRules.Verdict.Pending) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "心跳检查", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_RESUME, conv)
        }
        val content = open?.let { PendingIntent.getActivity(ctx, ("hbn-$conv").hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("计划还有 ${v.left} 步没完成")
            .setContentText("下一步：${v.next.take(60)} · 点按继续")
            .setAutoCancel(true)
            .apply { content?.let { setContentIntent(it) } }
            .build()
        runCatching { nm.notify(("hbn-$conv").hashCode(), n) }
    }
}
