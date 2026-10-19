package com.iris.localai.agent

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Serializable
enum class Repeat(val label: String) { NONE("一次"), DAILY("每天"), WEEKDAYS("工作日"), WEEKLY("每周") }

/** 1.12.0: one scheduled task: at [nextAt] IRIS posts a notification; tapping it runs [prompt]. */
@Serializable
data class Schedule(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String,
    /** What the agent is asked when the task runs; empty = a plain reminder. */
    val prompt: String = "",
    val nextAt: Long,
    val repeat: Repeat = Repeat.NONE,
    /** "HH:mm" the task repeats at (local time). */
    val time: String = "",
    /** 1.14.0: run on its own when IRIS is still running (else the notification asks). */
    val autoRun: Boolean = false,
    /** 1.14.0 功耗限制: only while charging; stop the run after [maxMinutes]. */
    val chargingOnly: Boolean = false,
    val maxMinutes: Int = 10,
)

/** Pure rules of schedules (unit-tested). */
object ScheduleRules {
    /** The next fire after [after] for a repeating task at [hour]:[minute] in [zone]; null = one-shot. */
    fun next(repeat: Repeat, hour: Int, minute: Int, after: Long, zone: ZoneId): Long? {
        if (repeat == Repeat.NONE) return null
        var t = Instant.ofEpochMilli(after).atZone(zone).withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!t.isAfter(Instant.ofEpochMilli(after).atZone(zone))) t = t.plusDays(1)
        fun ok(z: ZonedDateTime) = repeat != Repeat.WEEKDAYS || z.dayOfWeek.value <= 5
        while (!ok(t)) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }

    /** After a fire: the task with its next time, or null when it is done (one-shot). */
    fun afterFire(s: Schedule, now: Long, zone: ZoneId): Schedule? {
        if (s.repeat == Repeat.NONE) return null
        val (h, m) = PhoneTool.parseHourMinute(s.time) ?: Instant.ofEpochMilli(s.nextAt).atZone(zone).let { it.hour to it.minute }
        val base = maxOf(now, s.nextAt)
        val n = if (s.repeat == Repeat.WEEKLY) {
            var t = Instant.ofEpochMilli(s.nextAt).atZone(zone).withHour(h).withMinute(m)
            while (t.toInstant().toEpochMilli() <= base) t = t.plusWeeks(1)
            t.toInstant().toEpochMilli()
        } else next(s.repeat, h, m, base, zone) ?: return null
        return s.copy(nextAt = n)
    }

    fun describe(s: Schedule, zone: ZoneId): String {
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        return "${s.id}: ${s.title} — ${f.format(Instant.ofEpochMilli(s.nextAt))}" + (if (s.repeat != Repeat.NONE) " (${s.repeat.name.lowercase()})" else "") +
            (if (s.prompt.isNotBlank()) " → \"${s.prompt.take(60)}\"" else "") +
            (if (s.autoRun) " [auto" + (if (s.chargingOnly) ", charging only" else "") + ", ≤${s.maxMinutes}min]" else "")
    }

    /** 1.14.0: the settings-page line for a task's power rules. */
    fun limits(s: Schedule): String = if (!s.autoRun) "到点通知，点按执行" else
        "自动执行" + (if (s.chargingOnly) " · 仅充电时" else " · 电量≥${PowerGate.MIN_BATTERY}%") + " · 最长 ${s.maxMinutes} 分钟"
}

/** Saved schedules; [onChange] re-arms the alarms. */
class ScheduleStore(private val load: () -> String?, private val save: (String) -> Unit, private val onChange: (List<Schedule>) -> Unit = {}) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(Schedule.serializer())
    private val _items = MutableStateFlow(read())
    val items: StateFlow<List<Schedule>> = _items.asStateFlow()

    private fun read(): List<Schedule> = load()?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty()

    @Synchronized fun put(s: Schedule) = write(_items.value.filter { it.id != s.id } + s)
    @Synchronized fun remove(id: String): Boolean {
        val before = _items.value
        write(before.filter { it.id != id })
        return before.size != _items.value.size
    }
    @Synchronized fun replaceAll(list: List<Schedule>) = write(list)
    fun byId(id: String): Schedule? = _items.value.firstOrNull { it.id == id }

    private fun write(list: List<Schedule>) {
        val sorted = list.sortedBy { it.nextAt }.take(MAX)
        _items.value = sorted
        save(json.encodeToString(ser, sorted))
        onChange(sorted)
    }

    companion object { const val MAX = 50 }
}

/**
 * 1.12.0 `schedule`: reminders and scheduled agent tasks ("每天 8 点整理新闻"). At the time IRIS
 * posts a notification; tapping it opens a new 智能体 conversation that runs the prompt (the
 * model is not kept loaded in the background).
 */
object ScheduleTool : AgentTool {
    override val name = "schedule"
    override val label = "定时任务"
    override val description = "提醒或定时让智能体做一件事（到点发通知，点开即执行）"
    override val promptLine = "schedule(action: add|list|remove, time?: \"[yyyy-MM-dd ]HH:mm\", repeat?: none|daily|weekdays|weekly, prompt?, title?, id?, auto?: bool, charging_only?, max_minutes?)"
    override val risk = ToolRisk.WRITE
    override val group = ToolGroup.TASKS

    override fun run(args: JsonObject, env: ToolEnv): ToolResult {
        val store = env.schedules ?: return ToolResult(false, "Error: scheduling is not available", "不可用")
        val zone = env.zone()
        return when ((Args.str(args, "action", "op") ?: "add").trim().lowercase()) {
            "list" -> {
                val l = store.items.value
                ToolResult(true, if (l.isEmpty()) "No scheduled tasks." else l.joinToString("\n") { ScheduleRules.describe(it, zone) }, "${l.size} 个")
            }
            "remove", "delete", "cancel" -> {
                val id = Args.str(args, "id", "name", "title") ?: throw IllegalArgumentException("缺少参数 id")
                val hit = store.byId(id.trim()) ?: store.items.value.firstOrNull { it.title == id.trim() }
                    ?: return ToolResult(false, "Error: no scheduled task \"$id\"", "没找到")
                store.remove(hit.id)
                ToolResult(true, "Removed ${hit.id}: ${hit.title}", "已删除")
            }
            "add", "create", "set" -> {
                val title = Args.str(args, "title", "text", "name", "label") ?: Args.str(args, "prompt")?.take(30) ?: throw IllegalArgumentException("缺少参数 title")
                val t = Args.str(args, "time", "at", "when") ?: throw IllegalArgumentException("缺少参数 time")
                val repeat = when (Args.str(args, "repeat", "every")?.trim()?.lowercase()) {
                    "daily", "day", "everyday", "每天" -> Repeat.DAILY
                    "weekdays", "workdays", "工作日" -> Repeat.WEEKDAYS
                    "weekly", "week", "每周" -> Repeat.WEEKLY
                    else -> Repeat.NONE
                }
                val now = env.clock()
                val hm = PhoneTool.parseHourMinute(t)
                val at = when {
                    hm != null && repeat != Repeat.NONE && repeat != Repeat.WEEKLY -> ScheduleRules.next(repeat, hm.first, hm.second, now, zone)!!
                    else -> PhoneTool.parseDateTime(t, env)?.let { if (hm != null && it <= now) it + 86_400_000L else it }
                        ?: throw IllegalArgumentException("无法识别 time：$t（用 yyyy-MM-dd HH:mm 或 HH:mm）")
                }
                if (at <= now) throw IllegalArgumentException("时间已经过去了")
                val time = hm?.let { "%02d:%02d".format(it.first, it.second) } ?: Instant.ofEpochMilli(at).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
                val prompt = Args.str(args, "prompt", "task", "do").orEmpty().take(1000)
                val s = Schedule(
                    title = title.take(60), prompt = prompt, nextAt = at, repeat = repeat, time = time,
                    autoRun = prompt.isNotBlank() && (Args.bool(args, "auto", "auto_run", "unattended", "background") ?: false),
                    chargingOnly = Args.bool(args, "charging_only", "charging", "when_charging") ?: false,
                    maxMinutes = (Args.str(args, "max_minutes", "minutes", "limit")?.trim()?.toIntOrNull() ?: 10).coerceIn(1, 60),
                )
                store.put(s)
                ToolResult(true, "Scheduled ${ScheduleRules.describe(s, zone)}", "已设定")
            }
            else -> throw IllegalArgumentException("未知 action")
        }
    }
}

/** Arms one exact (or, without that permission, inexact) alarm per schedule. */
object ScheduleAlarms {
    const val ACTION = "com.iris.localai.SCHEDULE_FIRE"
    const val EXTRA_ID = "schedule_id"
    const val EXTRA_PROMPT = "iris_scheduled_prompt"
    const val EXTRA_TITLE = "iris_scheduled_title"
    const val CHANNEL = "iris_schedule"
    private const val PREFS = "iris_schedules"

    @Volatile private var instance: ScheduleStore? = null

    /** One store per process (the receiver and the UI share it). */
    fun store(ctx: Context): ScheduleStore = instance ?: synchronized(this) {
        instance ?: run {
            val app = ctx.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ScheduleStore({ prefs.getString("items", null) }, { prefs.edit().putString("items", it).commit() }) { arm(app, it) }.also { instance = it }
        }
    }

    private fun pi(ctx: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(ctx, id.hashCode(), Intent(ctx, ScheduleReceiver::class.java).setAction(ACTION).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private val armed = mutableSetOf<String>()

    @Synchronized fun arm(ctx: Context, list: List<Schedule>) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        (armed - list.map { it.id }.toSet()).forEach { am.cancel(pi(ctx, it)) }
        armed.clear()
        list.forEach { s ->
            val p = pi(ctx, s.id)
            val exact = runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.nextAt, p) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.nextAt, p)
            armed += s.id
        }
    }

    fun notify(ctx: Context, s: Schedule) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "定时任务", NotificationManager.IMPORTANCE_HIGH))
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (s.prompt.isNotBlank()) putExtra(EXTRA_PROMPT, s.prompt)
            putExtra(EXTRA_TITLE, s.title)
        }
        val content = open?.let { PendingIntent.getActivity(ctx, s.id.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(s.title)
            .setContentText(if (s.prompt.isNotBlank()) "点按让 IRIDIS 执行：${s.prompt.take(60)}" else "提醒")
            .setAutoCancel(true)
            .apply { content?.let { setContentIntent(it) } }
            .build()
        runCatching { nm.notify(s.id.hashCode(), n) }
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = ScheduleAlarms.store(context)
        when (intent.action) {
            Heartbeat.ACTION -> Heartbeat.onFire(context, intent)
            ScheduleAlarms.ACTION -> {
                val s = store.byId(intent.getStringExtra(ScheduleAlarms.EXTRA_ID) ?: return) ?: return
                // 1.14.0: unattended tasks run in the open IRIS when the power rules allow.
                val ran = s.autoRun && s.prompt.isNotBlank() && PowerGate.check(context, s.chargingOnly) &&
                    AgentBus.offer(AgentBus.Job(AgentBus.Kind.SCHEDULED, s.prompt, s.title, maxMinutes = s.maxMinutes))
                if (!ran) ScheduleAlarms.notify(context, s)
                val next = ScheduleRules.afterFire(s, System.currentTimeMillis(), ZoneId.systemDefault())
                if (next != null) store.put(next) else store.remove(s.id)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.TIME_SET", "android.intent.action.TIMEZONE_CHANGED" -> {
                // Missed while off: fire late once, then continue.
                val now = System.currentTimeMillis()
                val list = store.items.value.mapNotNull { s ->
                    if (s.nextAt > now) s else { ScheduleAlarms.notify(context, s); ScheduleRules.afterFire(s, now, ZoneId.systemDefault()) }
                }
                store.replaceAll(list)
            }
        }
    }
}
