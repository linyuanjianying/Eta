package io.github.mangi.eta.ui.screens.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.agent.prompt.NamedToolGroup
import io.github.mangi.eta.agent.prompt.ToolGroupStore
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.repository.AgentExperienceRepository
import io.github.mangi.eta.data.repository.AgentGoalRepository
import io.github.mangi.eta.data.repository.AgentKnowledgeRepository
import io.github.mangi.eta.data.repository.AgentTaskPlanStore
import io.github.mangi.eta.data.repository.KnowledgeDocument
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 知识库 / 目标 / 经验的本机管理页。
 *
 * 这三个仓库此前只有工具入口（模型可读写、用户看不见），这里补上人类可用的
 * 列表 / 查看 / 编辑 / 删除，数据仍走同一套带 revision 校验的仓库接口：
 * 编辑前先取最新 revision，冲突时提示重新加载，绝不静默覆盖用户内容。
 *
 * 目标与清单是「按会话」存储的，用一个会话 ID 输入框选择对象——不猜用户想改哪个会话。
 */
@Composable
internal fun AgentKnowledgeScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var documents by remember { mutableStateOf<List<KnowledgeDocument>>(emptyList()) }
    var editing by remember { mutableStateOf<KnowledgeDocument?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<KnowledgeDocument?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // 编辑器字段
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("reference") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingRevision by remember { mutableStateOf<String?>(null) }

    var goals by remember { mutableStateOf("") }
    var goalConversationId by remember { mutableStateOf("") }
    var goalText by remember { mutableStateOf("") }
    var goalCompleted by remember { mutableStateOf(false) }
    var planSummary by remember { mutableStateOf("") }
    var conversationChoices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    // 刷新信号：模型可能在同一时间用 knowledge_save 写入，用户回到本页时对象不会是新的
    // （仓库没有变更流），所以每次回到前台/重新进入都主动重读一次。
    var refreshTick by remember { mutableStateOf(0) }

    var experienceCount by remember { mutableStateOf(0) }
    var autoExperience by remember { mutableStateOf(false) }
    var smartRetain by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<NamedToolGroup>>(emptyList()) }

    fun reload() {
        scope.launch {
            // 三项读取各自独立容错：知识库损坏不应该连带经验计数与组合列表一起显示为空。
            val docs = withContext(Dispatchers.IO) {
                runCatching { AgentKnowledgeRepository.snapshot() }.getOrDefault(emptyList())
            }
            val count = withContext(Dispatchers.IO) {
                runCatching { AgentExperienceRepository.count() }.getOrDefault(0)
            }
            val loadedGroups = withContext(Dispatchers.IO) {
                runCatching { ToolGroupStore.load() }.getOrDefault(emptyList())
            }
            documents = docs
            experienceCount = count
            groups = loadedGroups
            autoExperience = runCatching { SettingsDataStore.autoExperienceEnabled() }.getOrDefault(false)
            smartRetain = runCatching { SettingsDataStore.smartRetainEnabled() }.getOrDefault(false)
            loaded = true
        }
    }

    LaunchedEffect(refreshTick) {
        reload()
        conversationChoices = withContext(Dispatchers.IO) {
            runCatching {
                io.github.mangi.eta.data.db.EtaDatabase.get(appContext)
                    .conversationDao().conversationMetadataRows()
                    .sortedByDescending { it.updatedAt }
                    .take(30)
                    .map { row -> row.id to row.title.ifBlank { "未命名会话" } }
            }.getOrDefault(emptyList())
        }
    }

    fun openEditor(document: KnowledgeDocument?) {
        editingId = document?.id
        editingRevision = document?.revision
        title = document?.title.orEmpty()
        content = document?.content.orEmpty()
        source = document?.source.orEmpty()
        tags = document?.tags.orEmpty().joinToString(", ")
        kind = document?.kind ?: "reference"
        editing = document
        creating = document == null
    }

    fun loadGoalFor(conversationId: String) {
        val id = conversationId.trim()
        goalConversationId = id
        if (id.isEmpty()) {
            goals = ""
            planSummary = ""
            goalCompleted = false
            return
        }
        scope.launch {
            val loadedGoal = withContext(Dispatchers.IO) {
                runCatching { AgentGoalRepository.goal(id) }.getOrDefault("")
            }
            val completed = withContext(Dispatchers.IO) {
                runCatching { AgentGoalRepository.isCompleted(id) }.getOrDefault(false)
            }
            val plan = withContext(Dispatchers.IO) {
                runCatching { AgentTaskPlanStore(appContext.filesDir).load(id).summary() }.getOrDefault("")
            }
            goals = loadedGoal
            goalText = loadedGoal
            goalCompleted = completed
            planSummary = plan
        }
    }

    val filtered = remember(documents, query) {
        val keyword = query.trim().lowercase()
        if (keyword.isEmpty()) documents.filterNot { it.deleted }
        else documents.filterNot { it.deleted }.filter { item ->
            item.title.lowercase().contains(keyword) ||
                item.source.lowercase().contains(keyword) ||
                item.content.lowercase().contains(keyword) ||
                item.tags.any { it.lowercase().contains(keyword) }
        }
    }

    MiuixScaffoldPage(
        title = "知识 / 目标 / 经验",
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        if (!loaded) return@MiuixScaffoldPage

        item(key = "kb-title") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallTitle("本地知识库（${filtered.size} 条）")
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { refreshTick += 1 }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "重新读取")
                }
            }
        }
        item(key = "kb-search") {
            Card {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索标题、来源、正文或标签",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    trailingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                )
            }
        }
        items(filtered.take(80), key = { "kb-${it.id}" }) { document ->
            Card {
                BasicComponent(
                    title = document.title,
                    summary = "${document.kind} · ${document.source.ifBlank { "无来源" }} · " +
                        "${document.content.length} 字符",
                    endActions = {
                        IconButton(onClick = { deleting = document }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除")
                        }
                    },
                    onClick = { openEditor(document) },
                )
            }
        }
        if (filtered.isEmpty()) {
            item(key = "kb-empty") {
                Card {
                    BasicComponent(
                        title = "没有知识条目",
                        summary = "模型可以用 knowledge_save 写入，也可以在这里手动新建",
                    )
                }
            }
        }
        item(key = "kb-new") {
            Card {
                BasicComponent(
                    title = "新建知识条目",
                    summary = "标题 + Markdown 正文；来源与标签可选",
                    startAction = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    onClick = { openEditor(null) },
                )
            }
        }
        message?.let { text ->
            item(key = "kb-message") {
                Text(
                    text = text,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        item(key = "goal-title") { SmallTitle("会话目标与子任务清单") }
        item(key = "goal-pick") {
            Card {
                if (conversationChoices.isEmpty()) {
                    BasicComponent(
                        title = "最近会话",
                        summary = "暂无可选会话；可手动填入会话 ID",
                    )
                } else {
                    conversationChoices.take(8).forEach { (id, title) ->
                        BasicComponent(
                            title = title,
                            summary = if (id == goalConversationId) "$id · 已选中" else id,
                            onClick = { loadGoalFor(id) },
                        )
                    }
                }
            }
        }
        item(key = "goal-id") {
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    TextField(
                        value = goalConversationId,
                        onValueChange = { goalConversationId = it },
                        label = "或手动填会话 ID",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        text = "读取该会话的目标",
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        onClick = { loadGoalFor(goalConversationId) },
                    )
                }
            }
        }
        item(key = "goal-edit") {
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    TextField(
                        value = goalText,
                        onValueChange = { goalText = it },
                        label = "目标正文（留空保存 = 清除目标）",
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = "保存目标",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val id = goalConversationId.trim()
                                if (id.isEmpty()) {
                                    message = "先填会话 ID"
                                } else {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { AgentGoalRepository.save(id, goalText) }
                                        }
                                        message = result.fold(
                                            onSuccess = { if (it.isBlank()) "目标已清除" else "目标已保存（${it.length} 字符）" },
                                            onFailure = { it.message ?: "目标保存失败" },
                                        )
                                        loadGoalFor(id)
                                    }
                                }
                            },
                        )
                        TextButton(
                            text = "清除目标",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val id = goalConversationId.trim()
                                if (id.isNotEmpty()) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { runCatching { AgentGoalRepository.clear(id) } }
                                        goalText = ""
                                        goals = ""
                                        planSummary = ""
                                        message = "目标已清除"
                                    }
                                }
                            },
                        )
                    }
                }
                BasicComponent(
                    title = "目标状态",
                    summary = when {
                        goalConversationId.isBlank() -> "未选择会话"
                        goalCompleted -> "已完成（重新保存目标会清除完成标记）"
                        goals.isBlank() -> "该会话没有目标"
                        else -> "进行中 · ${goals.length} 字符"
                    },
                )
                if (planSummary.isNotBlank()) {
                    BasicComponent(title = "子任务清单", summary = planSummary)
                }
            }
        }

        item(key = "exp-title") { SmallTitle("任务经验（$experienceCount 条）") }
        item(key = "exp") {
            Card {
                BasicComponent(
                    title = "自动记录任务经验",
                    summary = "只留工具名与错误码，不含参数、输出与聊天正文",
                    endActions = {
                        Switch(
                            checked = autoExperience,
                            onCheckedChange = { on ->
                                autoExperience = on
                                scope.launch { SettingsDataStore.setAutoExperienceEnabled(on) }
                            },
                        )
                    },
                )
                BasicComponent(
                    title = "智能保留长期事实",
                    summary = "从用户明确陈述里抽取结构化记忆；默认关闭",
                    endActions = {
                        Switch(
                            checked = smartRetain,
                            onCheckedChange = { on ->
                                smartRetain = on
                                scope.launch { SettingsDataStore.setSmartRetainEnabled(on) }
                            },
                        )
                    },
                )
                BasicComponent(
                    title = "清空全部任务经验",
                    summary = "只删除本机经验记录，不影响 MEMORY.md 与知识库",
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { AgentExperienceRepository.clear() }.isSuccess
                            }
                            message = if (ok) "任务经验已清空" else "清空失败"
                            experienceCount = withContext(Dispatchers.IO) {
                                runCatching { AgentExperienceRepository.count() }.getOrDefault(0)
                            }
                        }
                    },
                )
            }
        }
        if (groups.isNotEmpty()) {
            item(key = "groups-title") { SmallTitle("工具组合（${groups.size}）") }
            items(groups, key = { "group-${it.name}" }) { group ->
                Card {
                    BasicComponent(
                        title = group.name,
                        summary = "${group.tools.size} 个工具",
                    )
                }
            }
        }
    }

    deleting?.let { document ->
        val confirm: () -> Unit = {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { AgentKnowledgeRepository.delete(document.id, document.revision) }
                }
                message = result.fold(
                    onSuccess = { if (it) "已删除：${document.title}" else "条目已不存在" },
                    onFailure = { knowledgeErrorMessage(it) },
                )
                deleting = null
                reload()
            }
        }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiConfirmDialog(
                visible = true,
                title = "删除知识条目",
                message = "删除后本机不再显示；不会同步到其他设备（本项目未启用 WebDAV）。",
                confirmText = "删除",
                destructive = true,
                onConfirm = confirm,
                onDismissRequest = { deleting = null },
            )
        } else {
            WindowDialog(
                show = true,
                title = "删除知识条目",
                summary = "删除后本机不再显示；不会同步到其他设备（本项目未启用 WebDAV）。",
                cornerRadius = DialogDefaults.CornerRadius,
                onDismissRequest = { deleting = null },
            ) {
                MiuixDialogActions(
                    confirmText = "删除",
                    destructive = true,
                    onCancel = { deleting = null },
                    onConfirm = confirm,
                )
            }
        }
    }

    // 编辑器：COUI 与 Miuix 共用同一个「编辑知识」页，不再各自弹一种对话框。
    // 为什么不做成弹窗：COUI 的通用对话框只支持单行输入，塞不下类型/标题/正文/来源/标签
    // 五个字段；两套样式各写一套又会分叉。独立一屏两边都一样好用。
    if (creating || editing != null) {
        val save = {
            val t = title
            val c = content
            val id = editingId
            val rev = editingRevision
            creating = false
            editing = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        AgentKnowledgeRepository.save(
                            title = t, content = c, kind = kind, source = source,
                            tags = tags.split(',').map(String::trim).filter(String::isNotBlank).take(12),
                            id = id, revision = rev,
                        )
                    }
                }
                message = result.fold(
                    onSuccess = { "已保存：${it.title}" },
                    onFailure = { knowledgeErrorMessage(it) },
                )
                reload()
            }
        }
        MiuixScaffoldPage(
            title = if (editingId == null) "新增知识" else "编辑知识",
            onBack = { creating = false; editing = null },
            modifier = Modifier.imePadding(),
        ) {
            item(key = "editor") {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        KnowledgeEditorFields(
                            kind = kind, onKind = { kind = it },
                            title = title, onTitle = { title = it },
                            content = content, onContent = { content = it },
                            source = source, onSource = { source = it },
                            tags = tags, onTags = { tags = it },
                        )
                    }
                }
            }
            item(key = "editor-actions") {
                Column(modifier = Modifier.padding(12.dp)) {
                    TextButton(
                        text = "保存",
                        enabled = title.isNotBlank() && content.isNotBlank() && content.length <= 12_000,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { save() },
                    )
                    if (editingId != null) {
                        TextButton(
                            text = "删除此条",
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            onClick = {
                                val current = editing
                                creating = false
                                editing = null
                                if (current != null) deleting = current
                            },
                        )
                    }
                }
            }
        }
        return
    }
}

/** 与 AgentKnowledgeStore.save 接受的 kind 取值保持一致。 */
private val KNOWLEDGE_KINDS = listOf(
    "fact" to "事实",
    "procedure" to "步骤",
    "reference" to "参考",
)

/**
 * 仓储层异常文案统一是英文短句（同一套字符串也被工具与日志使用）。
 * 用户直接看到的提示给中文，同时保留冲突这一必须说明的情况。
 */
private fun knowledgeErrorMessage(error: Throwable): String {
    val raw = error.message.orEmpty()
    return when {
        raw.contains("KNOWLEDGE_CONFLICT") -> "该条目已被修改（可能是模型刚写入），请关闭后重新打开再改"
        raw.contains("Knowledge document not found") -> "条目已不存在，可能已被删除"
        raw.contains("title") -> "标题不能为空，且不超过 160 字符"
        raw.contains("body") -> "正文不能为空，且不超过 12000 字符"
        raw.contains("metadata") -> "来源或标签超限（来源 ≤250 字符，标签 ≤12 个、每个 ≤40 字符）"
        raw.contains("limit reached") || raw.contains("capacity") -> "知识库容量已满，先删除部分条目"
        raw.contains("exceeds") || raw.contains("too large") -> "知识库体积超限"
        raw.isNotBlank() -> "保存失败：$raw"
        else -> "保存失败"
    }
}

@Composable
private fun KnowledgeEditorFields(
    kind: String,
    onKind: (String) -> Unit,
    title: String,
    onTitle: (String) -> Unit,
    content: String,
    onContent: (String) -> Unit,
    source: String,
    onSource: (String) -> Unit,
    tags: String,
    onTags: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 类型决定模型的检索偏好：fact=可复用事实，procedure=步骤，reference=参考资料。
        Column {
            Text(
                text = "类型",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KNOWLEDGE_KINDS.forEach { (value, label) ->
                    TextButton(
                        text = if (value == kind) "✓ $label" else label,
                        modifier = Modifier.weight(1f),
                        onClick = { onKind(value) },
                    )
                }
            }
        }
        TextField(
            value = title,
            onValueChange = onTitle,
            label = "标题（≤160 字符）",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = content,
            onValueChange = onContent,
            label = "Markdown 正文（≤12000 字符）",
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = source,
            onValueChange = onSource,
            label = "来源（网址、文件或用户提供）",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = tags,
            onValueChange = onTags,
            label = "标签（逗号分隔，最多 12 个）",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "编辑前读取的是最新 revision；保存时若被模型改过会报冲突，请重新打开该条再改。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontWeight = FontWeight.Normal,
        )
    }
}
