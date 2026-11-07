package io.github.mangi.eta.ui.screens.subagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.prompt.NamedToolGroup
import io.github.mangi.eta.agent.prompt.ToolGroupStore
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.agent.tool.AgentToolNaming
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 子代理预算与档案总览。
 *
 * 上游已经有「模型 / 思考强度」两个子代理设置；这里补的是 fork 里真正起作用的增量：
 * 轮次预算分档的可见性、档案工具白名单的实况，以及把常用组合直接存成档案可用的清单。
 * 轮次预算由主代理在 subagent 调用里用 complexity 声明，界面上写明档位以免用户以为能在这里硬改。
 */
@Composable
internal fun SubagentBudgetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var autoExperience by remember { mutableStateOf(false) }
    var smartRetain by remember { mutableStateOf(false) }
    var semantic by remember { mutableStateOf(false) }
    var embeddings by remember { mutableStateOf(false) }
    var graph by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<NamedToolGroup>>(emptyList()) }
    var profileCount by remember { mutableStateOf(0) }
    var modelLabel by remember { mutableStateOf("跟随主模型") }

    LaunchedEffect(Unit) {
        runCatching {
            autoExperience = SettingsDataStore.autoExperienceEnabled()
            smartRetain = SettingsDataStore.smartRetainEnabled()
            semantic = SettingsDataStore.semanticMemoryEnabled()
            embeddings = SettingsDataStore.semanticMemoryUseEmbeddings()
            graph = SettingsDataStore.graphMemoryEnabled()
            groups = ToolGroupStore.load()
            profileCount = SubagentProfileCatalog.listAll().size
            val settings = SettingsDataStore.subagentSettings()
            val picked = settings.modelId?.let { id ->
                ProviderRepository.allProviders().firstNotNullOfOrNull { provider ->
                    provider.models.firstOrNull { it.id == id }?.let { "${provider.name} · ${it.displayName}" }
                }
            }
            if (picked != null) {
                val effort = ReasoningEffort.fromWireValue(settings.reasoningEffort)?.wireValue
                modelLabel = if (effort.isNullOrBlank()) picked else "$picked · $effort"
            }
        }
        loaded = true
    }

    MiuixScaffoldPage(
        title = "子代理预算与记忆",
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        if (!loaded) return@MiuixScaffoldPage

        item(key = "budget-title") { SmallTitle("轮次预算") }
        item(key = "budget") {
            Column {
                Card {
                    BasicComponent(
                        title = "simple / normal / complex = 10 / 20 / 40 轮",
                        summary = "主代理在 subagent 调用里按工作量声明；未声明时按任务说明估算",
                    )
                    BasicComponent(
                        title = "当前子代理模型",
                        summary = modelLabel,
                    )
                    BasicComponent(
                        title = "已加载档案（$profileCount）",
                        summary = "档案的工具白名单在 schema 层生效：被禁用的工具根本不出现",
                    )
                }
                Text(
                    text = "轮次上限是硬性的：到顶即停并回报剩余部分，不会无限烧 token。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        item(key = "groups-title") { SmallTitle("可用工具组合（档案 tools 可套用）") }
        if (groups.isEmpty()) {
            item(key = "groups-empty") {
                Card {
                    BasicComponent(
                        title = "还没有保存的组合",
                        summary = "在「工具边界与组合」里把当前白名单存成组合，这里会按名称列出",
                    )
                }
            }
        }
        groups.forEach { group ->
            item(key = "group-${group.name}") {
                Card {
                    BasicComponent(
                        title = group.name,
                        summary = group.tools.size.toString() + " 个工具：" +
                            group.tools.take(4).joinToString("、") { AgentToolNaming.face(it).label },
                    )
                }
            }
        }
        item(key = "groups-hint") {
            Text(
                text = "在 subagent / subagent_profile 的 tools 里写这些工具名即可，未列出的保持档案默认。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        item(key = "memory-title") { SmallTitle("记忆与检索（影响子代理可用的背景）") }
        item(key = "memory") {
            Card {
                BasicComponent(
                    title = "任务经验记录",
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
                    summary = "从用户明确陈述里抽取；抽取失败不影响本轮结果",
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
                    title = "语义检索（聊天模型重排）",
                    summary = "用主模型判断相关性，不需要额外服务",
                    endActions = {
                        Switch(
                            checked = semantic,
                            onCheckedChange = { on ->
                                semantic = on
                                scope.launch { SettingsDataStore.setSemanticMemoryEnabled(on) }
                            },
                        )
                    },
                )
                BasicComponent(
                    title = "向量检索（embeddings）",
                    summary = "需要 OpenAI 兼容的 /embeddings 端点；失败自动回落词法检索",
                    endActions = {
                        Switch(
                            checked = embeddings,
                            onCheckedChange = { on ->
                                embeddings = on
                                scope.launch { SettingsDataStore.setSemanticMemoryUseEmbeddings(on) }
                            },
                        )
                    },
                )
                BasicComponent(
                    title = "关联记忆扩展（图检索）",
                    summary = "沿已审核的关系边补一层相关事实",
                    endActions = {
                        Switch(
                            checked = graph,
                            onCheckedChange = { on ->
                                graph = on
                                scope.launch { SettingsDataStore.setGraphMemoryEnabled(on) }
                            },
                        )
                    },
                )
            }
        }
        item(key = "provider-hint") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "以上开关默认关闭；语义检索、智能保留和向量检索可能向所选模型服务发送问题或候选记忆片段，并产生额外用量。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
