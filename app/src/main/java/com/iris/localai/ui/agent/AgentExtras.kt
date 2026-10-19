package com.iris.localai.ui.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.agent.Permission
import com.iris.localai.agent.ScheduleRules
import com.iris.localai.agent.SearchProvider
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisDialog
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.CheckRow
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import com.iris.localai.util.Backup
import com.iris.localai.util.LogExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * 1.12.0 sections of 设置 › 智能体: 联网搜索 (provider), 命令规则 (run_command prefixes),
 * 定时任务, 技能, 备份与日志.
 */
@Composable
fun AgentExtraSections() {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    val s by agent.settings.settings.collectAsStateWithLifecycle()
    val schedules by agent.schedules.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val c = Iris.colors

    // ---- 联网搜索 ----
    SectionTitle("联网搜索")
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            SettingsRow("搜索服务", subtitle = s.search.label)
            // 2.0.0: six services no longer fit a segmented control: one check row each.
            SearchProvider.entries.forEach { p ->
                Divider()
                CheckRow(p.short, selected = s.search == p) {
                    agent.settings.update { it.copy(searchProvider = p.name, searchProviderChosen = true) }
                }
            }
            if (s.search == SearchProvider.SEARXNG) {
                var url by remember(s.searxUrl) { mutableStateOf(s.searxUrl) }
                OutlinedTextField(
                    url, { url = it; agent.settings.update { st -> st.copy(searxUrl = it.trim()) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = 8.dp),
                    singleLine = true, label = { Text("SearXNG 地址，如 https://searx.example.org") },
                    shape = IrisRadius.mediumShape, colors = irisTextFieldColors(),
                )
            }
        }
    }
    GroupFootnote("「谷歌/必应（浏览器）」在内置浏览器里搜索，和 浏览器 页共用登录与 Cookie；遇到人机验证时请在 浏览器 页手动完成一次，本次先改用必应。其他服务只把搜索词发给所选服务。自建 SearXNG 需要公网地址（为安全起见，智能体不能访问本机和局域网）。")

    // ---- 命令规则 ----
    SectionTitle("命令规则")
    SettingsGroup {
        val rules = s.commandRules.entries.sortedBy { it.key }
        if (rules.isEmpty()) SettingsRow("还没有规则", subtitle = "确认「运行命令」时选「总是允许」，会记住这条命令的开头（如 git status）")
        rules.forEachIndexed { i, (prefix, perm) ->
            if (i > 0) Divider()
            val p = Permission.parse(perm) ?: Permission.ASK
            SettingsRow(prefix, subtitle = p.label, trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PressableIcon(Icons.Outlined.Close, "删除规则") { agent.settings.update { it.copy(commandRules = it.commandRules - prefix) } }
                }
            }, onClick = {
                val next = when (p) { Permission.ALLOW -> Permission.ASK; Permission.ASK -> Permission.DENY; Permission.DENY -> Permission.ALLOW }
                agent.settings.update { it.copy(commandRules = it.commandRules + (prefix to next.name)) }
            })
        }
        Divider()
        var draft by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                draft, { draft = it }, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("命令开头，如 rm（默认设为拒绝）", style = IrisType.secondary) },
                textStyle = IrisType.mono.copy(color = c.text), shape = IrisRadius.mediumShape, colors = irisTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            )
            Spacer(Modifier.width(8.dp))
            PressableIcon(Icons.Outlined.Add, "添加规则", enabled = draft.isNotBlank()) {
                val k = draft.trim().replace(Regex("\\s+"), " ")
                agent.settings.update { it.copy(commandRules = it.commandRules + (k to Permission.DENY.name)) }
                draft = ""
            }
        }
    }
    GroupFootnote("只读命令（ls、cat、grep…）自动放行；点一条规则可切换 允许 / 询问 / 拒绝。")

    // ---- 定时任务 ----
    SectionTitle("定时任务")
    SettingsGroup {
        if (schedules.isEmpty()) SettingsRow("没有定时任务", subtitle = "对智能体说「每天早上 8 点帮我整理新闻」即可创建")
        schedules.forEachIndexed { i, sc ->
            if (i > 0) Divider()
            // 1.14.0: tap cycles 通知 → 自动执行 → 自动执行（仅充电） (功耗限制).
            SettingsRow(sc.title, subtitle = ScheduleRules.describe(sc, ZoneId.systemDefault()).substringAfter(": ").substringBefore(" [auto") + "\n" + ScheduleRules.limits(sc), subtitleMaxLines = 3,
                onClick = if (sc.prompt.isBlank()) null else {
                    {
                        agent.schedules.put(when {
                            !sc.autoRun -> sc.copy(autoRun = true, chargingOnly = false)
                            !sc.chargingOnly -> sc.copy(chargingOnly = true)
                            else -> sc.copy(autoRun = false, chargingOnly = false)
                        })
                    }
                },
                trailing = {
                PressableIcon(Icons.Outlined.Close, "删除") { agent.schedules.remove(sc.id) }
            })
        }
    }
    GroupFootnote("到点发通知，点按后在新对话里执行；点一条可改为「自动执行」（IRIDIS 开着且模型已加载时）。")

    // ---- 技能 ----
    SectionTitle("技能")
    val skills by produceState(emptyList<com.iris.localai.agent.SkillInfo>(), s) {
        value = withContext(Dispatchers.IO) { agent.skills.invalidate(); agent.skills.all() }
    }
    SettingsGroup {
        if (skills.isEmpty()) SettingsRow("没有技能")
        skills.forEachIndexed { i, sk ->
            if (i > 0) Divider()
            SettingsRow(sk.name, subtitle = sk.description.ifBlank { "（无说明）" } + " · " + sk.source, subtitleMaxLines = 2)
        }
    }
    GroupFootnote("自定义技能放在工作区 skills/<名字>/SKILL.md。")
}

/**
 * 备份与日志 (导出备份 / 从备份恢复 / 导出日志). Lives on 设置 › 存储空间 since 2.0.x: it is about
 * the app's data, not the agent.
 */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    var message by remember { mutableStateOf<String?>(null) }
    // 2.1.0: restore shows what the backup holds first; export asks whether to include the API key.
    var confirmRestore by remember { mutableStateOf<Pair<android.net.Uri, Backup.Info>?>(null) }
    var askExport by remember { mutableStateOf(false) }
    var includeApiKey by remember { mutableStateOf(false) }
    val c = Iris.colors
    // ---- 备份与日志 ----
    SectionTitle("备份与日志")
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val withKey = includeApiKey
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use {
                        Backup.export(Backup.dataDir(context), it, com.iris.localai.BuildConfig.VERSION_NAME,
                            workspace = agent.sandbox.root, includeApiKey = withKey)
                    }
                }.fold({ "已备份 $it 个文件" + if (withKey) "（含 API 密钥，请妥善保管）" else "" }, { "备份失败：${it.message}" })
            }
        }
    }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)!!.use { Backup.inspect(it) } } }
            info.onSuccess { confirmRestore = uri to it }.onFailure { message = "无法读取备份：${it.message}" }
        }
    }
    val exportLogs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(LogExport.collect().toByteArray()) } }
                    .fold({ "日志已导出" }, { "导出失败：${it.message}" })
            }
        }
    }
    SettingsGroup {
        SettingsRow("导出备份", subtitle = "对话、记忆、技能、模型配置和设置（不含模型与语音包）", onClick = { includeApiKey = false; askExport = true })
        Divider()
        SettingsRow("导入备份", subtitle = "会替换现在的对话和设置，完成后 IRIDIS 自动重启", onClick = { pickRestore.launch(arrayOf("application/zip", "application/octet-stream")) })
        Divider()
        SettingsRow("导出日志", subtitle = "本次运行的日志，反馈问题时附上", onClick = {
            exportLogs.launch("IRIS-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".txt")
        })
    }

    if (askExport) {
        IrisDialog(
            onDismiss = { askExport = false }, title = "导出备份",
            confirmText = "选择保存位置",
            onConfirm = {
                askExport = false
                exportBackup.launch("IRIDIS-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".zip")
            },
        ) {
            Column {
                Text("包含对话、记忆、技能、模型配置和设置。", style = IrisType.secondary, color = c.textSecondary)
                com.iris.localai.ui.components.SwitchRow("包含 API 密钥", checked = includeApiKey,
                    subtitle = "本地 API 服务的密钥；默认不导出") { includeApiKey = it }
            }
        }
    }
    confirmRestore?.let { (uri, info) ->
        val m = info.manifest
        val date = if (m.createdAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(m.createdAt)) else "未知"
        val version = m.appVersion.ifBlank { if (info.legacy) "IRIS LocalAI（旧版）" else "未知" }
        IrisDialog(
            onDismiss = { confirmRestore = null }, title = "导入备份？",
            confirmText = "导入并重启", destructive = true,
            onConfirm = {
                confirmRestore = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(uri)!!.use { Backup.restore(Backup.dataDir(context), it, workspace = agent.sandbox.root) } }
                    }
                    r.onSuccess { Backup.restart(context) }.onFailure { message = "导入失败：${it.message}" }
                }
            },
        ) {
            Text(
                "版本：$version\n日期：$date\n文件数：${info.dataFiles}" + (if (m.includesApiKey && !info.legacy) "\n包含 API 密钥" else "") +
                    "\n\n现在的对话、记忆和设置会被备份里的内容替换，导入完成后 IRIDIS 会自动重启。",
                style = IrisType.secondary, color = c.textSecondary,
            )
        }
    }
    message?.let { m ->
        IrisDialog(onDismiss = { message = null }, title = m, confirmText = "好", onConfirm = { message = null }, dismissText = null)
    }
}
