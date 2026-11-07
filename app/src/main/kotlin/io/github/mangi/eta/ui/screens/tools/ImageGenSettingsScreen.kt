package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.image.ImageGenClient
import io.github.mangi.eta.agent.image.ImageGenConfig
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 生图中转站设置。
 *
 * 只支持 OpenAI 兼容的 `/v1/images/generations`：填 base_url + key + 模型名即可，
 * 需要异步任务轮询的服务（可灵/即梦等）协议各不相同，不在这条路径内。
 */
@Composable
internal fun ImageGenSettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf<String?>(null) }

    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    fun persist(message: String? = null) {
        saving = true
        scope.launch {
            SettingsDataStore.setImageGenSettings(
                SettingsDataStore.ImageGenSettings(
                    enabled = enabled,
                    baseUrl = baseUrl.trim(),
                    apiKey = apiKey.trim(),
                    model = model.trim(),
                ),
            )
            saving = false
            savedHint = message ?: "已保存"
        }
    }

    LaunchedEffect(Unit) {
        val s = SettingsDataStore.imageGenSettings()
        enabled = s.enabled
        baseUrl = s.baseUrl
        apiKey = s.apiKey
        model = s.model
        loaded = true
    }

    if (showPicker) {
        ModelPickerDialog(
            models = models,
            query = query,
            onQueryChange = { query = it },
            onPick = { id ->
                model = id
                showPicker = false
                persist(message = "已选择 $id")
            },
            onDismiss = { showPicker = false },
        )
    }

    MiuixScaffoldPage(
        title = "生图（中转站）",
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        if (!loaded) return@MiuixScaffoldPage

        item(key = "enable_title") { SmallTitle("能力") }
        item(key = "enable") {
            Column {
                Card {
                    BasicComponent(
                        title = "启用生图工具",
                        summary = if (enabled) "模型可调用 generate_image" else "关闭时该工具不进入工具列表",
                        endActions = {
                            Switch(checked = enabled, onCheckedChange = {
                                enabled = it
                                persist(message = if (it) "生图工具已启用" else "生图工具已关闭")
                            })
                        },
                    )
                }
                ImageGenNote(
                    "只有 base_url、API Key、模型名三项齐全且开关打开时，工具才会出现在模型手里。",
                )
            }
        }

        item(key = "conn_title") { SmallTitle("中转站") }
        item(key = "conn") {
            Column {
                Card {
                    ImageGenFieldColumn {
                        ImageGenField(label = "base_url", value = baseUrl, onChange = { baseUrl = it })
                        ImageGenField(
                            label = "API Key",
                            value = apiKey,
                            secret = true,
                            onChange = { apiKey = it },
                        )
                    }
                }
                ImageGenNote("base_url 可写 https://host 或 https://host/v1，会自动补 /images/generations。")
            }
        }

        item(key = "model_title") { SmallTitle("模型") }
        item(key = "model") {
            Column {
                Card {
                    ImageGenFieldColumn {
                        ImageGenField(label = "模型名", value = model, onChange = { model = it })
                    }
                }
                ImageGenNote("可用下面的「从远端拉取模型」直接选，避免手打模型 id。")
            }
        }
        item(key = "fetch") {
            Card {
                BasicComponent(
                    title = if (fetching) "正在拉取模型…" else "从远端拉取模型",
                    summary = fetchError ?: "读取 /models 列表，选中后自动填入上面的模型名",
                    onClick = if (fetching) null else {
                        {
                            fetchError = null
                            fetching = true
                            scope.launch {
                                val cfg = ImageGenConfig.fromSettings(baseUrl, apiKey, model, enabled)
                                ImageGenClient.fetchModels(cfg)
                                    .onSuccess { list ->
                                        models = sortModelsForPicker(list)
                                        query = ""
                                        showPicker = true
                                    }
                                    .onFailure { fetchError = it.message ?: "拉取失败" }
                                fetching = false
                            }
                        }
                    },
                )
            }
        }

        item(key = "save") {
            ImageGenSaveBlock(saving = saving, hint = savedHint, onSave = { savedHint = null; persist() })
        }
    }
}

@Composable
private fun ImageGenNote(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ImageGenFieldColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun ImageGenField(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    var visible by rememberSaveable(label) { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation()
        else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Rounded.Visibility
                        else Icons.Rounded.VisibilityOff,
                        contentDescription = null,
                    )
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun ImageGenSaveBlock(
    saving: Boolean,
    hint: String?,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextButton(
            text = if (saving) "保存中…" else "保存",
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = onSave,
        )
        hint?.let { message ->
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val filtered = remember(models, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) models else models.filter { it.lowercase().contains(q) }
    }
    WindowDialog(
        show = true,
        title = "选择模型（共 ${models.size} 个）",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                label = "搜索模型",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "没有匹配的模型",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(filtered) { id ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = id,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 图像类模型排前面：模型多到手翻很难找，先把明显能生图的挑出来。 */
private val IMAGE_MODEL_HINTS = listOf(
    "image", "dall-e", "dalle", "flux", "nano-banana", "nano_banana",
    "seedream", "seededit", "kling", "kolors", "qwen-image", "imagen",
    "recraft", "ideogram", "grok-imagine", "mai-image", "hidream", "muse",
)

private fun sortModelsForPicker(models: List<String>): List<String> =
    models.sortedWith(
        compareByDescending<String> { id ->
            IMAGE_MODEL_HINTS.any { id.lowercase().contains(it) }
        }.thenBy { it },
    )
