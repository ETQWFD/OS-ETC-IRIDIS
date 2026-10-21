package com.iris.localai.ui.agent

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.agent.AlpineRootfs
import com.iris.localai.agent.CommandBackend
import com.iris.localai.agent.TermuxBridge
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.IrisButton
import com.iris.localai.ui.components.IrisButtonStyle
import com.iris.localai.ui.components.PressableIcon
import com.iris.localai.ui.components.SegmentedControl
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SubpageScaffold
import com.iris.localai.ui.components.irisTextFieldColors
import com.iris.localai.ui.theme.Iris
import com.iris.localai.ui.theme.IrisDimens
import com.iris.localai.ui.theme.IrisRadius
import com.iris.localai.ui.theme.IrisType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 1.11.0 texts of the 终端 / 浏览器 pages (unit-tested). */
object ToolPageText {
    const val TERMINAL_NAV = "在工作区里运行命令；可接入 Termux 获得完整 Linux"
    const val BROWSER_NAV = "智能体使用的内置浏览器；可在这里查看或手动登录"
    const val BACKEND = "命令环境"
    const val BACKEND_NOTE = "内置：系统自带 sh + toybox，只能在工作区里操作。Termux：完整 Linux（bash、apt、python、git…）。未安装外部 Termux 时会自动使用内置中文核心（一键下载安装，无需另外装应用）；已授权外部 Termux 则优先用完整版。智能体的「运行命令」也会改用它。"
    const val ALPINE_NOTE = "Alpine 在 IRIDIS 内部运行（PRoot，无需 root），能用 apk 装 python、git、nodejs 等。工作区挂在 /workspace；cd 和 export 会保留到下一条命令。单条命令最长 120 秒，后台进程随命令结束。"
    const val BROWSER_NOTE = "智能体用的就是这个浏览器（Cookie 只保存在 IRIDIS 里，不影响你的其他浏览器）。需要登录的网站可以先在这里手动登录。智能体的「浏览器」工具默认关闭，开启后每次询问。"
}

@Composable
fun TerminalPage(onBack: () -> Unit, onOpenFiles: (() -> Unit)? = null) {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    val s by agent.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // 1.12.2: shared with the agent's run_command — its commands show here too.
    val entries by com.iris.localai.agent.TerminalLog.entries.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var termuxProblem by remember { mutableStateOf(TermuxBridge.problem(context)) }
    val alpine by AlpineRootfs.state.collectAsStateWithLifecycle()
    val coreState by com.iris.localai.agent.TermuxCore.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        AlpineRootfs.refresh(context)
        com.iris.localai.agent.TermuxCore.refresh(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { termuxProblem = TermuxBridge.problem(context) }
    val coreInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { termuxProblem = TermuxBridge.problem(context) }
    fun installCore() {
        scope.launch {
            withContext(Dispatchers.IO) { com.iris.localai.agent.TermuxCore.install(context) }
        }
    }
    val list = rememberLazyListState()
    val c = Iris.colors
    val reduceMotion = com.iris.localai.ui.theme.LocalReduceMotion.current

    fun run() {
        val cmd = input.trim()
        if (cmd.isEmpty() || running) return
        input = ""
        running = true
        val id = com.iris.localai.agent.TerminalLog.start(cmd, byAgent = false)
        scope.launch {
            val (shell, kind) = agent.shell()
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    shell.run(cmd, agent.sandbox.root, if (kind == CommandBackend.BUILTIN) 30_000L else 120_000L, 128 * 1024)
                }.getOrElse { com.iris.localai.agent.ShellOutput(-1, "错误：${it.message}", false, false) }
            }
            val text = buildString {
                append(out.output.trimEnd())
                if (out.truncated) append("\n…[输出已截断]")
                if (out.timedOut) append("\n[超时已终止]")
            }
            com.iris.localai.agent.TerminalLog.finish(id, text, out.exitCode)
            running = false
            agent.changed()
        }
    }
    LaunchedEffect(entries.size, entries.lastOrNull()?.exit) { if (entries.isNotEmpty()) list.animateScrollToItem(entries.lastIndex + 1) }

    SubpageScaffold("终端", onBack, actions = {
        if (onOpenFiles != null) PressableIcon(Icons.Outlined.Folder, "文件", onClick = onOpenFiles)
        PressableIcon(Icons.Outlined.DeleteSweep, "清空", enabled = entries.isNotEmpty()) { com.iris.localai.agent.TerminalLog.clear() }
    }) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                item {
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                            SettingsRow(ToolPageText.BACKEND, subtitle = when (s.backend) {
                                // ETC fork: external Termux first, otherwise the built-in Chinese core.
                                CommandBackend.TERMUX -> when {
                                    termuxProblem == null -> "Termux 已就绪"
                                    com.iris.localai.agent.TermuxCore.isInstalled(context) -> "内置 Termux 中文核心 v${com.iris.localai.agent.TermuxCore.VERSION} · 无需外部应用"
                                    else -> "未安装：点击下方一键下载内置 Termux"
                                }
                                CommandBackend.PROOT -> if (alpine == AlpineRootfs.State.Ready) "Alpine ${AlpineRootfs.VERSION} · 工作区在 /workspace" else "Alpine 未安装"
                                CommandBackend.BUILTIN -> "工作区：" + agent.sandbox.root.path
                            })
                            val opts = listOf(CommandBackend.BUILTIN, CommandBackend.PROOT, CommandBackend.TERMUX)
                            SegmentedControl(
                                opts.map { it.short }, opts.indexOf(s.backend),
                                { i -> agent.settings.update { it.copy(commandBackend = opts[i].name) } },
                                Modifier.fillMaxWidth().padding(horizontal = IrisDimens.rowPadding),
                            )
                        }
                        // ETC fork: one-click install of the built-in Chinese Termux core.
                        com.iris.localai.ui.components.RevealSection(s.backend == CommandBackend.TERMUX && termuxProblem != null) {
                            Divider()
                            if (!TermuxBridge.isInstalled(context)) {
                                val coreReady = com.iris.localai.agent.TermuxCore.isInstalled(context)
                                if (!coreReady) {
                                    val progress = coreState is com.iris.localai.agent.TermuxCore.State.Downloading
                                    SettingsRow(
                                        when (coreState) {
                                            is com.iris.localai.agent.TermuxCore.State.Downloading ->
                                                "正在下载内置 Termux 中文核心…（${(coreState as com.iris.localai.agent.TermuxCore.State.Downloading).done / 1024 / 1024} / ${(coreState as com.iris.localai.agent.TermuxCore.State.Downloading).total / 1024 / 1024} MB）"
                                            com.iris.localai.agent.TermuxCore.State.Unpacking -> "正在解压安装…"
                                            is com.iris.localai.agent.TermuxCore.State.Failed -> "安装失败：${(coreState as com.iris.localai.agent.TermuxCore.State.Failed).message}"
                                            else -> "一键安装内置 Termux（中文 · 32 位 · 无需外部应用）"
                                        },
                                        subtitle = "从本项目 GitHub Release 下载中文核心（约 27 MB），安装后即可直接使用",
                                        onClick = {
                                            if (coreState !is com.iris.localai.agent.TermuxCore.State.Downloading && coreState != com.iris.localai.agent.TermuxCore.State.Unpacking) installCore()
                                        },
                                    )
                                } else {
                                    SettingsRow("内置 Termux 中文核心已就绪", subtitle = "v${com.iris.localai.agent.TermuxCore.VERSION} · 命令直接在此运行，无需外部 Termux")
                                }
                                SettingsRow("仍想用外部 Termux？", subtitle = "GitHub 或 F-Droid 版（Google Play 版过旧，不支持）", onClick = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TermuxBridge.DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                })
                            } else {
                                SettingsRow("授权 IRIDIS 使用 Termux", subtitle = "系统会询问是否允许在 Termux 中运行命令", onClick = { permission.launch(TermuxBridge.PERMISSION) })
                            }
                        }
                    }
                    com.iris.localai.ui.components.RevealSection(s.backend == CommandBackend.TERMUX) {
                        GroupFootnote(
                            if (TermuxBridge.isInstalled(context)) "首次使用请在 Termux 里运行：\n" + TermuxBridge.SETUP
                            else "已选择 Termux 后端：未检测到外部 Termux 时，命令会自动改用内置中文核心（无需安装任何东西）；检测到并授权外部 Termux 后自动切换到完整版。内置核心软件源为北京清华 TUNA 镜像。",
                            monospace = true,
                        )
                    }
                    com.iris.localai.ui.components.RevealSection(s.backend == CommandBackend.PROOT) { AlpineSection(alpine) }
                    GroupFootnote(ToolPageText.BACKEND_NOTE)
                    Spacer(Modifier.padding(top = 12.dp))
                }
                items(entries, key = { it.id }) { e ->
                    SelectionContainer(if (reduceMotion) Modifier else Modifier.animateItem()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.groupGutter, vertical = 4.dp)) {
                            if (e.byAgent) Text("智能体 · " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(e.at)), style = IrisType.caption, color = c.textTertiary)
                            Text("$ " + e.command, style = IrisType.mono, color = c.accent)
                            val body = when {
                                e.exit == null -> ""
                                e.output.isEmpty() -> "(无输出)"
                                else -> e.output
                            }
                            if (e.exit == null) com.iris.localai.ui.components.TypingDots(Modifier.padding(vertical = 6.dp))
                            else Text(body, style = IrisType.monoSmall, color = c.text)
                            if (e.exit != null && e.exit != 0) Text("[退出码 ${e.exit}]", style = IrisType.monoSmall, color = c.danger)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space12, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    input, { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = IrisType.mono.copy(color = c.text),
                    placeholder = { Text(if (s.backend != CommandBackend.BUILTIN) "命令，如 python3 -V" else "命令，如 ls -la", style = IrisType.mono) },
                    shape = IrisRadius.mediumShape,
                    colors = irisTextFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSend = { run() }),
                )
                Spacer(Modifier.width(8.dp))
                PressableIcon(Icons.AutoMirrored.Outlined.Send, "运行", size = 44.dp, enabled = input.isNotBlank() && !running, background = c.accent, tint = c.onAccent) { run() }
            }
        }
    }
}

@Composable
fun BrowserPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val agent = remember { (context.applicationContext as IrisApp).container.agent }
    val browser = agent.browser
    val st by browser.status.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(st.url) }
    LaunchedEffect(st.url) { url = st.url }
    val c = Iris.colors
    val web = remember { browser.webView() }
    DisposableEffect(Unit) {
        onDispose {
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            browser.layoutOffscreen(web)
        }
    }
    SubpageScaffold("浏览器", onBack, actions = {
        PressableIcon(Icons.AutoMirrored.Outlined.ArrowBack, "后退", enabled = web.canGoBack()) { web.goBack() }
        PressableIcon(Icons.Outlined.Refresh, "刷新") { web.reload() }
    }) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = IrisDimens.space12, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    url, { url = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = IrisType.secondary.copy(color = c.text),
                    placeholder = { Text("输入网址或搜索", style = IrisType.secondary) },
                    shape = IrisRadius.mediumShape,
                    colors = irisTextFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { web.loadUrl(com.iris.localai.agent.BrowserText.normalizeUrl(url)) }),
                )
            }
            if (st.loading) com.iris.localai.ui.components.IrisProgressBar(progress = null, height = 2.dp, track = c.outline.copy(alpha = 0.4f))
            Box(Modifier.weight(1f).fillMaxWidth().clip(IrisRadius.mediumShape).background(c.surface)) {
                AndroidView(factory = {
                    (web.parent as? android.view.ViewGroup)?.removeView(web)
                    web
                }, modifier = Modifier.fillMaxSize())
            }
            GroupFootnote(ToolPageText.BROWSER_NOTE, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}


/** 1.12.0: install / remove the Alpine rootfs for the built-in Linux terminal. */
@Composable
private fun AlpineSection(state: AlpineRootfs.State) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf(false) }
    Spacer(Modifier.padding(top = 8.dp))
    SettingsGroup {
        when (state) {
            AlpineRootfs.State.Ready -> SettingsRow("删除 Alpine", subtitle = "删掉 Linux 系统和装过的软件包（工作区文件不受影响）", onClick = { confirmRemove = true })
            is AlpineRootfs.State.Downloading -> SettingsRow("正在下载 Alpine", subtitle = "${state.done / 1024} / ${state.total / 1024} KB")
            AlpineRootfs.State.Unpacking -> SettingsRow("正在解压…")
            is AlpineRootfs.State.Failed -> SettingsRow("重新安装 Alpine", subtitle = "上次失败：" + state.message, onClick = {
                scope.launch(Dispatchers.IO) { AlpineRootfs.install(context) }
            })
            AlpineRootfs.State.Idle -> SettingsRow(
                if (AlpineRootfs.supported(context)) "安装 Alpine Linux" else "此设备不支持",
                subtitle = "下载约 4 MB（Alpine ${AlpineRootfs.VERSION} 官方 minirootfs，校验 SHA-256）",
                onClick = if (AlpineRootfs.supported(context)) ({ scope.launch(Dispatchers.IO) { AlpineRootfs.install(context) } }) else null,
            )
        }
    }
    GroupFootnote(ToolPageText.ALPINE_NOTE)
    if (confirmRemove) {
        com.iris.localai.ui.components.IrisDialog(
            onDismiss = { confirmRemove = false }, title = "删除 Alpine？",
            confirmText = "删除", destructive = true, onConfirm = { confirmRemove = false; scope.launch(Dispatchers.IO) { AlpineRootfs.uninstall(context) } },
            dismissText = "取消", onDismissClick = { confirmRemove = false },
        ) { Text("装过的软件包（python、git 等）会一起删除。", style = IrisType.secondary, color = Iris.colors.textSecondary) }
    }
}
