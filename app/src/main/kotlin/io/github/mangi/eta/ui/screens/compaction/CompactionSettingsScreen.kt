package io.github.mangi.eta.ui.screens.compaction

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.agent.model.AgentCompactionTarget
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.CompactionSettings
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.ModelBrandMark
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiChipTabs
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSearchBarBox
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.filterGroups
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val DEFAULT_PROMPT_HINT = "留空表示使用内置默认提示词。无工具、整段非空输出、容量校验与失败保留原文由程序保证。"

/**
 * 上下文压缩设置：独立压缩模型、压缩思考强度与自定义摘要提示词。
 *
 * 只读写 [CompactionSettings]；自动压缩开关（Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED）由设置页
 * 的“自动压缩上下文”开关管理，本页不改动也不重复展示。模型为空表示跟随当前任务模型，
 * 实际回落逻辑在 RuntimeConfigRepository.compactionTarget 中完成。
 */
@Composable
internal fun CompactionSettingsScreen(onBack: () -> Unit) {
    val settingsFlow = remember { SettingsDataStore.compactionSettingsFlow() }
    var settings by remember { mutableStateOf(CompactionSettings()) }
    LaunchedEffect(settingsFlow) {
        settingsFlow.collect { settings = it }
    }

    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val primaryProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val primaryModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }

    val save: (CompactionSettings) -> Unit = { value ->
        scope.launch {
            runCatching { SettingsDataStore.setCompactionSettings(value) }
                .onSuccess {
                    settings = value
                    error = null
                }
                .onFailure { error = "保存失败：${it.javaClass.simpleName}" }
        }
    }

    val pickerState = remember(providers, primaryProviderId, primaryModelId) {
        AgentModelPickerProjector.project(providers, primaryProviderId, primaryModelId)
    }
    val primaryModelName = pickerState.selectedModel?.displayName
    val selectedTarget = remember(providers, settings.modelId) {
        settings.modelId?.takeIf { it.isNotBlank() }?.let { providers.enabledModel(it) }
    }
    val targetSummary = when {
        settings.modelId != null && selectedTarget == null -> "所选模型已停用或删除，请重新选择"
        selectedTarget != null -> "${selectedTarget.provider.name} / ${selectedTarget.model.displayName}"
        !primaryModelName.isNullOrBlank() -> "跟随当前任务模型（$primaryModelName）"
        else -> "跟随当前任务模型"
    }

    // 思考强度按实际生效的压缩模型推导；跟随主模型时取主模型能力。
    val effectiveModel = remember(providers, settings.modelId, selectedTarget, primaryModelId) {
        selectedTarget ?: providers.enabledModel(primaryModelId)
    }
    val capabilities = remember(effectiveModel) {
        effectiveModel?.let { (provider, model) ->
            runCatching { RuntimeConfigRepository.reasoningCapabilities(provider, model) }.getOrNull()
        }
    }
    val effortOptions = remember(capabilities) {
        listOf<ReasoningEffort?>(null) + capabilities?.selectableEfforts.orEmpty()
    }
    val effortLabels = remember(effortOptions) {
        effortOptions.map { effort ->
            when (effort) {
                null -> "跟随模型默认"
                ReasoningEffort.DEFAULT -> "Auto"
                else -> effort.displayName
            }
        }
    }
    val selectedEffortIndex = remember(effortOptions, settings.reasoningEffort) {
        effortOptions.indexOf(ReasoningEffort.fromWireValue(settings.reasoningEffort)).coerceAtLeast(0)
    }
    val onEffortChange: (Int) -> Unit = { index ->
        save(settings.copy(reasoningEffort = effortOptions.getOrNull(index)?.wireValue))
    }

    var thresholdDraft by remember(settings.fallbackThresholdTokens) {
        mutableStateOf(settings.effectiveThresholdTokens.toString())
    }
    val thresholdValue = thresholdDraft.toIntOrNull()
    val thresholdValid = thresholdValue != null && thresholdValue in
        CompactionSettings.MIN_THRESHOLD_TOKENS..CompactionSettings.MAX_THRESHOLD_TOKENS
    val thresholdHint = "当前任务模型有上下文窗口时，优先按模型窗口的 85% 自动压缩（人工设置优先于模型声明）。" +
        "模型没有窗口信息时，达到这里设置的 token 数再压缩。范围：1,024～10,000,000 token；保存后下次任务生效。"
    val saveThreshold = {
        if (thresholdValid) save(settings.copy(fallbackThresholdTokens = thresholdValue))
    }

    @Composable
    fun ThresholdContent() {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                CouiTextField(
                    value = thresholdDraft,
                    onValueChange = { thresholdDraft = it },
                    label = "通用压缩阈值（token）",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    showClearButton = false,
                )
                CouiText(text = thresholdHint, kind = CouiTextKind.PreferenceSummary,
                    modifier = Modifier.padding(vertical = 8.dp))
                CouiButton(text = "保存阈值", enabled = thresholdValid && thresholdValue != settings.effectiveThresholdTokens,
                    onClick = saveThreshold)
                error?.let { CouiText(text = it, kind = CouiTextKind.PreferenceSummary) }
            } else {
                TextField(value = thresholdDraft, onValueChange = { thresholdDraft = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth())
                Text(text = thresholdHint, style = MiuixTheme.textStyles.footnote2,
                    modifier = Modifier.padding(vertical = 8.dp))
                TextButton(text = "保存阈值", enabled = thresholdValid && thresholdValue != settings.effectiveThresholdTokens,
                    onClick = saveThreshold)
                error?.let { Text(text = it, color = StatusError) }
            }
            if (!thresholdValid) {
                if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                    CouiText(text = "请输入 1,024～10,000,000 之间的整数", kind = CouiTextKind.PreferenceSummary)
                } else Text(text = "请输入 1,024～10,000,000 之间的整数", color = StatusError)
            }
        }
    }

    var showModelSheet by remember { mutableStateOf(false) }
    var draft by remember(settings.prompt) {
        mutableStateOf(settings.prompt.ifBlank { AgentCompactionTarget.DEFAULT_PROMPT })
    }
    val draftDirty = draft.trim() != settings.prompt.trim()
    val onResetPrompt = {
        draft = AgentCompactionTarget.DEFAULT_PROMPT
        save(settings.copy(prompt = ""))
    }
    val onSavePrompt = {
        val trimmed = draft.trim()
        if (trimmed == AgentCompactionTarget.DEFAULT_PROMPT) {
            draft = AgentCompactionTarget.DEFAULT_PROMPT
            save(settings.copy(prompt = ""))
        } else {
            save(settings.copy(prompt = trimmed))
        }
    }

    @Composable
    fun ModelSheetContent() {
        CompactionModelPickerContent(
            pickerState = pickerState,
            selectedModelId = settings.modelId,
            primaryModelName = primaryModelName,
            onSelectModel = { modelId ->
                showModelSheet = false
                save(settings.copy(modelId = modelId))
            },
        )
    }

    val scopeSummary = "主代理、子代理以及手动/自动压缩统一使用这里的设置，只影响压缩摘要，不改变任务本身的模型。" +
        "所选服务商会接收待压缩的对话历史；压缩失败时保留原历史，不会自动改用其他模型。"

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            CouiScaffoldPage(
                title = "上下文压缩",
                onBack = onBack,
                overlay = {
                    Dialog(
                        visible = showModelSheet,
                        onDismissRequest = { showModelSheet = false },
                        dark = dark,
                        title = "压缩模型",
                        confirmAction = CouiDialogAction(
                            text = "关闭",
                            onClick = { showModelSheet = false },
                        ),
                        placement = CouiDialogPlacement.Bottom,
                        material = rememberCouiDialogMaterial(dark),
                        backdrop = rememberCouiWindowBackdrop(),
                    ) {
                        ModelSheetContent()
                    }
                },
            ) {
                Group(title = "压缩模型") {
                    CouiPreference(
                        title = "模型",
                        summary = targetSummary,
                        onClick = { showModelSheet = true },
                    )
                    if (effortOptions.size > 1) {
                        Divider()
                        CouiDropdownPreference(
                            title = "思考强度",
                            items = effortLabels,
                            selectedIndex = selectedEffortIndex,
                            onSelectedIndexChange = onEffortChange,
                        )
                    }
                }

                CouiText(
                    text = scopeSummary,
                    kind = CouiTextKind.PreferenceSummary,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )

                Group(title = "自动压缩阈值") { ThresholdContent() }

                CouiSectionTitle("摘要提示词")
                Group {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        CouiTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = "摘要策略",
                            singleLine = false,
                            minLines = 8,
                            maxLines = 16,
                            showClearButton = false,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CouiButton(
                                text = "恢复默认",
                                modifier = Modifier.weight(1f),
                                role = CouiButtonRole.Secondary,
                                onClick = onResetPrompt,
                            )
                            CouiButton(
                                text = "保存提示词",
                                modifier = Modifier.weight(1f),
                                role = CouiButtonRole.Primary,
                                enabled = draftDirty,
                                onClick = onSavePrompt,
                            )
                        }
                    }
                }
                CouiText(
                    text = DEFAULT_PROMPT_HINT,
                    kind = CouiTextKind.PreferenceSummary,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
                error?.let {
                    CouiText(
                        text = it,
                        kind = CouiTextKind.PreferenceSummary,
                        color = StatusError,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                    )
                }

                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = "上下文压缩",
                onBack = onBack,
                overlay = {
                    OverlayBottomSheet(
                        show = showModelSheet,
                        title = "压缩模型",
                        onDismissRequest = { showModelSheet = false },
                        insideMargin = DpSize(0.dp, 0.dp),
                    ) {
                        ModelSheetContent()
                    }
                },
            ) {
                item(key = "model_group") {
                    SmallTitle("压缩模型")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = "模型",
                            summary = targetSummary,
                            onClick = { showModelSheet = true },
                        )
                        if (effortOptions.size > 1) {
                            OverlayDropdownPreference(
                                title = "思考强度",
                                items = effortLabels,
                                selectedIndex = selectedEffortIndex,
                                onSelectedIndexChange = onEffortChange,
                            )
                        }
                    }
                    Text(
                        text = scopeSummary,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }

                item(key = "threshold_group") {
                    SmallTitle("通用压缩阈值（token）")
                    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                        ThresholdContent()
                    }
                }

                item(key = "prompt_group") {
                    SmallTitle("摘要提示词")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            TextField(
                                value = draft,
                                onValueChange = { draft = it },
                                minLines = 8,
                                maxLines = 16,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    text = "恢复默认",
                                    modifier = Modifier.weight(1f),
                                    onClick = onResetPrompt,
                                )
                                TextButton(
                                    text = "保存提示词",
                                    modifier = Modifier.weight(1f),
                                    enabled = draftDirty,
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                    onClick = onSavePrompt,
                                )
                            }
                            Text(
                                text = DEFAULT_PROMPT_HINT,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            error?.let {
                                Text(
                                    text = it,
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = StatusError,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 压缩模型选择器：复用模型选择的分组、搜索与服务商筛选，首项为“跟随当前任务模型”。
 * 两种界面风格共享同一份内容，仅在搜索框与筛选标签处切换实现。
 */
@Composable
private fun CompactionModelPickerContent(
    pickerState: AgentModelPickerUiState,
    selectedModelId: String?,
    primaryModelName: String?,
    onSelectModel: (String?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    val filteredGroups = remember(pickerState.providerGroups, query, providerFilter) {
        pickerState.filterGroups(query)
            .filter { providerFilter == null || it.providerId == providerFilter }
    }
    val density = LocalDensity.current
    val sheetHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.68f
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(sheetHeight)
            .then(if (isCoui) Modifier else Modifier.navigationBarsPadding()),
    ) {
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiSearchBarBox(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "搜索模型或服务商",
                    backgroundColor = Color.Transparent,
                )
                if (pickerState.providerGroups.size > 1) {
                    val tabItems = remember(pickerState.providerGroups) {
                        listOf("" to "全部") + pickerState.providerGroups.map { it.providerId to it.providerName }
                    }
                    CouiChipTabs(
                        items = tabItems,
                        selectedKey = providerFilter.orEmpty(),
                        onSelectedKeyChange = { key -> providerFilter = key.takeIf { it.isNotEmpty() } },
                        backgroundColor = Color.Transparent,
                    )
                }
            }

            AppearanceUiStyle.MIUIX -> {
                InputField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = "搜索模型或服务商",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (pickerState.providerGroups.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "filter:all") {
                            EtaChoiceChip(
                                text = "全部",
                                selected = providerFilter == null,
                                onClick = { providerFilter = null },
                            )
                        }
                        items(
                            items = pickerState.providerGroups,
                            key = { "filter:${it.providerId}" },
                        ) { group ->
                            EtaChoiceChip(
                                text = group.providerName,
                                selected = providerFilter == group.providerId,
                                onClick = {
                                    providerFilter = if (providerFilter == group.providerId) null else group.providerId
                                },
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (query.isBlank() && providerFilter == null) {
                item(key = "inherit_primary") {
                    CompactionModelRow(
                        title = "跟随当前任务模型",
                        subtitle = primaryModelName?.let { "当前：$it" } ?: "默认使用会话正在使用的主模型",
                        modelId = pickerState.selectedModel?.modelId,
                        sourceType = pickerState.selectedModel?.providerSourceType,
                        selected = selectedModelId == null,
                        onClick = { onSelectModel(null) },
                    )
                }
            }
            filteredGroups.forEach { group ->
                item(key = "header:${group.providerId}") {
                    Text(
                        text = group.providerName,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(
                    items = group.models,
                    key = { model -> "model:${model.providerId}:${model.id}" },
                ) { model ->
                    CompactionModelRow(
                        title = model.displayName,
                        subtitle = model.modelId.takeIf { !it.equals(model.displayName, ignoreCase = true) },
                        modelId = model.modelId,
                        sourceType = model.providerSourceType,
                        selected = model.id == selectedModelId,
                        onClick = { onSelectModel(model.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactionModelRow(
    title: String,
    subtitle: String?,
    modelId: String?,
    sourceType: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .squircleSurface(
                color = if (selected) {
                    MiuixTheme.colorScheme.tertiaryContainer
                } else {
                    MiuixTheme.colorScheme.surface.copy(alpha = 0f)
                },
                cornerRadius = 14.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelBrandMark(
            modelId = modelId,
            sourceType = sourceType,
            size = 20.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MiuixTheme.colorScheme.onTertiaryContainer
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
