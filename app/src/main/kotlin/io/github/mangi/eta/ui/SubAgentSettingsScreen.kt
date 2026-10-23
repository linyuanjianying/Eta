package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentRole
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaOverlayDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 子代理设置：总开关、并行上限，以及每个子代理独立的名字、角色和模型。
 * 模型留空表示跟随主代理当前使用的模型。
 */
@Composable
internal fun SubAgentSettingsScreen(onBack: () -> Unit) {
    var enabled by remember { mutableStateOf(SubAgentPreferences.enabled()) }
    var limit by remember { mutableStateOf(SubAgentPreferences.parallelLimit()) }
    var profiles by remember { mutableStateOf(SubAgentPreferences.profiles()) }
    var providers by remember { mutableStateOf<List<ProviderSetting>>(emptyList()) }
    var editing by remember { mutableStateOf<SubAgentProfile?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        providers = withContext(Dispatchers.IO) {
            runCatching { ProviderRepository.allProviders().filter { provider -> provider.isEnabled } }
                .getOrDefault(emptyList())
        }
    }

    fun reload() {
        profiles = SubAgentPreferences.profiles()
    }

    MiuixScaffoldPage(title = "子代理", onBack = onBack) {
        item(key = "subagent_switch_title") {
            EtaPreferenceGroupTitle("总开关")
        }
        item(key = "subagent_switch") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                EtaSwitchPreference(
                    title = "允许主代理分派子代理",
                    summary = "开启后主代理可使用 delegate_task、get_task_result、cancel_task。" +
                        "子代理只读、不能再次分派，也不能使用终端或图形界面。",
                    checked = enabled,
                    onCheckedChange = { value ->
                        enabled = value
                        SubAgentPreferences.setEnabled(value)
                    },
                )
                EtaPreferenceDivider()
                EtaOverlayDropdownPreference(
                    title = "单个子代理并行上限",
                    summary = "同一子代理同时运行的任务数，超出时新的分派会被拒绝。",
                    items = List(SubAgentPreferences.MAX_PARALLEL) { (it + 1).toString() },
                    selectedIndex = (limit - 1).coerceIn(0, SubAgentPreferences.MAX_PARALLEL - 1),
                    onSelectedIndexChange = { index ->
                        val value = index + 1
                        limit = value
                        SubAgentPreferences.setParallelLimit(value)
                    },
                )
            }
        }

        item(key = "subagent_list_title") {
            EtaPreferenceGroupTitle("子代理列表")
        }
        item(key = "subagent_list") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                profiles.forEach { profile ->
                    EtaArrowPreference(
                        title = profile.name,
                        summary = profile.role.label + " · " + modelSummary(profile, providers) +
                            if (profile.enabled) "" else " · 已停用",
                        onClick = { editing = profile },
                    )
                    EtaPreferenceDivider()
                }
                EtaArrowPreference(
                    title = "新增子代理",
                    summary = "为它选择角色和模型的 AI 配置",
                    onClick = {
                        val created = SubAgentPreferences.add()
                        reload()
                        editing = created
                    },
                )
            }
        }
        item(key = "subagent_hint") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(
                    text = "子代理任务在后台运行，主代理回复结束后仍会继续。完成后用 get_task_result 读取结果，" +
                        "主代理需要自己检查结果是否符合要求。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    editing?.let { current ->
        SubAgentProfileDialog(
            profile = current,
            providers = providers,
            onDismiss = { editing = null },
            onSave = { updated ->
                SubAgentPreferences.update(updated.id) { updated }
                editing = null
                reload()
            },
            onDelete = {
                SubAgentPreferences.remove(current.id)
                editing = null
                reload()
            },
        )
    }
}

private fun modelSummary(profile: SubAgentProfile, providers: List<ProviderSetting>): String {
    if (profile.providerId.isBlank() || profile.modelId.isBlank()) return "跟随主代理模型"
    val provider = providers.firstOrNull { it.id == profile.providerId } ?: return "模型不可用"
    val model = provider.models.firstOrNull { it.id == profile.modelId } ?: return "${provider.name} · 模型不可用"
    return "${provider.name} · ${model.displayName.ifBlank { model.modelId }}"
}

@Composable
private fun SubAgentProfileDialog(
    profile: SubAgentProfile,
    providers: List<ProviderSetting>,
    onDismiss: () -> Unit,
    onSave: (SubAgentProfile) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var role by remember(profile.id) { mutableStateOf(profile.role) }
    var enabled by remember(profile.id) { mutableStateOf(profile.enabled) }
    var providerId by remember(profile.id) { mutableStateOf(profile.providerId) }
    var modelId by remember(profile.id) { mutableStateOf(profile.modelId) }
    var reasoning by remember(profile.id) { mutableStateOf(profile.reasoning) }

    val modelOptions: List<Pair<String, Pair<String, String>>> = buildList {
        providers.forEach { provider ->
            provider.models.filter { it.isEnabled }.forEach { model ->
                add(
                    "${provider.name} · ${model.displayName.ifBlank { model.modelId }}" to (provider.id to model.id),
                )
            }
        }
    }
    val modelLabels = listOf("跟随主代理模型") + modelOptions.map { it.first }
    val selectedModelIndex =
        modelOptions.indexOfFirst { it.second.first == providerId && it.second.second == modelId }
            .let { if (it >= 0) it + 1 else 0 }

    val reasoningLabels = listOf("跟随模型默认") + ReasoningEffort.entries.map { it.wireValue }
    val selectedReasoning = reasoning?.let { effort -> ReasoningEffort.entries.indexOf(effort) + 1 } ?: 0

    EtaWindowDialog(
        show = true,
        title = "编辑子代理",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = "名称",
                modifier = Modifier.fillMaxWidth(),
            )
            EtaOverlayDropdownPreference(
                title = "角色",
                summary = "影响子代理的系统提示与任务描述。",
                items = SubAgentRole.entries.map { it.label },
                selectedIndex = SubAgentRole.entries.indexOf(role).coerceAtLeast(0),
                onSelectedIndexChange = { index -> role = SubAgentRole.entries[index] },
            )
            EtaOverlayDropdownPreference(
                title = "模型",
                summary = "跟随主代理时使用当前对话选择的模型。",
                items = modelLabels,
                selectedIndex = selectedModelIndex,
                onSelectedIndexChange = { index ->
                    if (index == 0) {
                        providerId = ""
                        modelId = ""
                    } else {
                        val value = modelOptions.getOrNull(index - 1)?.second
                        providerId = value?.first.orEmpty()
                        modelId = value?.second.orEmpty()
                    }
                },
            )
            EtaOverlayDropdownPreference(
                title = "思考强度",
                summary = "跟随模型默认时使用该模型自身的思考档位。",
                items = reasoningLabels,
                selectedIndex = selectedReasoning,
                onSelectedIndexChange = { index ->
                    reasoning = if (index == 0) null else ReasoningEffort.entries.getOrNull(index - 1)
                },
            )
            EtaSwitchPreference(
                title = "启用",
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
        }
        MiuixDialogActions(
            confirmText = "保存",
            cancelText = "取消",
            onCancel = onDismiss,
            onConfirm = {
                onSave(
                    profile.copy(
                        name = name.trim().ifBlank { profile.name },
                        role = role,
                        enabled = enabled,
                        providerId = providerId,
                        modelId = modelId,
                        reasoning = reasoning,
                    ),
                )
            },
        )
        EtaArrowPreference(title = "删除这个子代理", onClick = onDelete)
    }
}
