package io.github.mangi.eta.ui.screens.prompts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
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
import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.prompt.AgentToolPolicyStore
import io.github.mangi.eta.agent.prompt.NamedToolGroup
import io.github.mangi.eta.agent.prompt.ToolGroupStore
import io.github.mangi.eta.agent.prompt.ToolPolicyMode
import io.github.mangi.eta.agent.tool.AgentToolNaming
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工具白名单与组合。
 *
 * 与「界面隐藏」不同：白名单同时作用于请求里的 tools 数组（模型看不到）和执行侧的兜底拒绝
 * （模型从历史里拼出调用也会被拒）。组合是可复用的命名清单，套用即替换当前白名单。
 */
@Composable
internal fun PromptToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var policy by remember { mutableStateOf(AgentToolPolicyStore.load()) }
    var groups by remember { mutableStateOf(ToolGroupStore.load()) }
    var search by remember { mutableStateOf("") }
    var newGroupName by remember { mutableStateOf("") }
    var names by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(Unit) {
        // 候选 = 装备目录里登记过的本地工具 + 由运行时另行拼装的工具
        // （subagent 系列、会话历史）。后者不在 requirements 里，之前无法被加入白名单，
        // 结果是「白名单里根本找不到它们」——对用户来说等于不可用。
        names = runCatching {
            (AgentToolRequirements.knownToolNames() +
                io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog.names +
                // 由运行时按条件发布、不进装备目录的工具：会话历史、子任务清单、生图。
                setOf(
                    io.github.mangi.eta.agent.model.AgentConversationToolCatalog.READ_HISTORY,
                    io.github.mangi.eta.agent.tool.AgentTaskTool.TASK_WRITE,
                    io.github.mangi.eta.agent.image.AgentImageGenToolCatalog.GENERATE_IMAGE,
                ))
                .filter(String::isNotBlank)
                .sorted()
        }.getOrDefault(emptyList())
    }

    fun persist(next: AgentToolPolicy) {
        if (AgentToolPolicyStore.save(next)) {
            policy = next
        } else {
            toast(context, "保存失败：本地配置不可写")
        }
    }

    val keyword = search.trim().lowercase()
    val filtered = remember(names, keyword) {
        if (keyword.isEmpty()) names else names.filter { name ->
            name.lowercase().contains(keyword) ||
                AgentToolNaming.face(name).label.lowercase().contains(keyword)
        }
    }

    MiuixScaffoldPage(
        title = "工具边界与组合",
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        item(key = "mode") {
            Column {
                SmallTitle("开放范围")
                Card {
                    BasicComponent(
                        title = "只开放白名单（${policy.allowedTools.size} 个）",
                        summary = if (policy.mode == ToolPolicyMode.ALLOW_LIST) {
                            "模型只能看到并调用白名单里的工具"
                        } else {
                            "当前全部开放；打开后只放行下面勾选的工具"
                        },
                        endActions = {
                            Switch(
                                checked = policy.mode == ToolPolicyMode.ALLOW_LIST,
                                onCheckedChange = { checked ->
                                    persist(
                                        policy.withMode(
                                            if (checked) ToolPolicyMode.ALLOW_LIST else ToolPolicyMode.ALLOW_ALL,
                                        ),
                                    )
                                },
                            )
                        },
                    )
                }
            }
        }

        item(key = "search") {
            Card {
                TextField(
                    value = search,
                    onValueChange = { search = it },
                    label = "搜索工具（支持中文名）",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    trailingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                )
            }
        }

        item(key = "tools-title") {
            SmallTitle("工具（${filtered.size}）")
        }
        items(filtered.chunked(12), key = { chunk -> chunk.firstOrNull().orEmpty() }) { chunk ->
            Card {
                Column {
                    chunk.forEach { name ->
                        val face = AgentToolNaming.face(name)
                        val checked = policy.allowedTools.contains(name)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = face.label, style = MiuixTheme.textStyles.body2)
                                Text(
                                    text = "$name · ${face.category}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Switch(
                                checked = checked,
                                onCheckedChange = { on ->
                                    if (policy.mode != ToolPolicyMode.ALLOW_LIST) {
                                        toast(context, "先打开「只开放白名单」再逐个勾选")
                                    } else {
                                        val next = if (on) policy.allowedTools + name else policy.allowedTools - name
                                        persist(policy.withAllowed(next))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        item(key = "groups-title") { SmallTitle("工具组合") }
        item(key = "group-new") {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = "组合名称（≤24 字）",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            if (ToolGroupStore.saveGroup(newGroupName, policy.allowedTools)) {
                                groups = ToolGroupStore.load()
                                newGroupName = ""
                                toast(context, "已保存当前白名单为组合")
                            } else {
                                toast(context, "组合名称为空或保存失败")
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "保存组合")
                    }
                }
            }
        }
        items(groups, key = { it.name }) { group ->
            Card {
                BasicComponent(
                    title = group.name,
                    summary = group.tools.size.toString() + " 个工具：" +
                        group.tools.take(4).joinToString("、") { AgentToolNaming.face(it).label },
                    endActions = {
                        IconButton(
                            onClick = {
                                ToolGroupStore.deleteGroup(group.name)
                                groups = ToolGroupStore.load()
                            },
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "删除组合")
                        }
                    },
                    onClick = {
                        persist(policy.withAllowed(group.tools).withMode(ToolPolicyMode.ALLOW_LIST))
                        toast(context, "已套用组合「${group.name}」")
                    },
                )
            }
        }

        item(key = "save") {
            Column(modifier = Modifier.padding(12.dp)) {
                TextButton(
                    text = if (policy.mode == ToolPolicyMode.ALLOW_LIST) {
                        "全选当前工具到白名单"
                    } else {
                        "切到白名单模式以逐个勾选"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (policy.mode == ToolPolicyMode.ALLOW_LIST) {
                            persist(policy.withAllowed(policy.allowedTools + names.toSet()))
                        } else {
                            persist(policy.withMode(ToolPolicyMode.ALLOW_LIST))
                        }
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "白名单同时作用于请求侧与执行侧",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}
