package com.iris.localai.ui.agent

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.localai.IrisApp
import com.iris.localai.knowledge.KnowledgeBase
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.GroupFootnote
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SettingsRow
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.icons.IrisIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 2.1.0 texts of the 知识库 section (unit-tested). */
object KnowledgeText {
    const val TITLE = "知识库"
    const val TOGGLE = "引用知识库"
    const val TOGGLE_SUB = "对话时自动找出最相关的 3 段资料一起交给模型（文件夹为空时不生效）；智能体可用「知识库」工具检索"
    const val REBUILD = "重建索引"
    const val ADD = "添加文件"
    const val NOTE = "支持 .txt .md .csv .json .html 等文本文件（PDF / Word 请先另存为文本）。文件放进文件夹即可，下次提问时自动更新索引，全部在本机完成。"

    fun status(files: Int, chunks: Int, unsupported: Int): String =
        if (files == 0) "还没有文件" else "$files 个文件 · $chunks 段" + if (unsupported > 0) " · $unsupported 个不支持" else ""

    fun folder(workspace: String): String = "用文件管理器把文件放进：$workspace/${KnowledgeBase.FOLDER}"
}

/** 2.1.0 知识库: status, 重建索引, 添加文件 (copied into knowledge/), the folder, and 引用知识库. */
@Composable
fun KnowledgeSection() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as IrisApp).container }
    val agent = container.agent
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val ws by agent.workspace.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    val status by produceState<KnowledgeBase.Status?>(null, tick, ws) {
        value = withContext(Dispatchers.IO) { runCatching { agent.knowledge.refresh() }.getOrNull() }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.count { uri ->
                    runCatching {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file.txt"
                        val bytes = context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= KnowledgeBase.MAX_BYTES } }
                        bytes != null && agent.knowledge.add(name, bytes) != null
                    }.getOrDefault(false)
                }
            }
            busy = false
            tick++
            Toast.makeText(context, if (added == uris.size) "已添加 $added 个文件" else "已添加 $added / ${uris.size} 个文件（过大或无法读取的已跳过）", Toast.LENGTH_SHORT).show()
        }
    }

    SectionTitle(KnowledgeText.TITLE)
    SettingsGroup {
        SettingsRow(
            "文件夹", icon = IrisIcons.Folder,
            subtitle = (status?.let { KnowledgeText.status(it.files, it.chunks, it.unsupported) } ?: "正在读取…") + "\n" + KnowledgeText.folder(ws.root.path),
            subtitleMaxLines = 4,
        )
        Divider()
        SettingsRow(KnowledgeText.ADD, icon = IrisIcons.Prompt, enabled = !busy, subtitle = "从手机里选文件，复制进知识库文件夹", onClick = {
            pick.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
        })
        Divider()
        SettingsRow(KnowledgeText.REBUILD, icon = IrisIcons.Reload, enabled = !busy, subtitle = "重新读取全部文件（一般不需要：文件变化会自动更新）", onClick = {
            busy = true
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { agent.knowledge.refresh(force = true) } }
                busy = false
                tick++
            }
        })
        Divider()
        SwitchRow(KnowledgeText.TOGGLE, settings.knowledgeInChat, subtitle = KnowledgeText.TOGGLE_SUB, icon = IrisIcons.Memory) { on ->
            container.settings.update { it.copy(knowledgeInChat = on) }
        }
    }
    GroupFootnote(KnowledgeText.NOTE)
}
