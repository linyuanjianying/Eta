package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.OpenCodeEmbeddedGateway
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 内嵌 OpenCode 网关设置。
 *
 * 默认开启本地网关：对话请求走 127.0.0.1 上由本 APK 自带的 opencode2api 进程，
 * 关掉即回到「直接访问外部 Zen API」。上游 Key 只用于网关向 Zen 取模型/推理，
 * 不会写进日志；界面只显示状态，不回显密钥。
 */
@Composable
internal fun OpenCodeGatewayScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var zenKey by remember { mutableStateOf("") }
    var goKey by remember { mutableStateOf("") }
    var proxy by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var readiness by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching {
            OpenCodeEmbeddedGateway.init(context.applicationContext)
            enabled = OpenCodeEmbeddedGateway.enabled()
            zenKey = OpenCodeEmbeddedGateway.zenKey()
            goKey = OpenCodeEmbeddedGateway.goKey()
            proxy = OpenCodeEmbeddedGateway.upstreamProxy()
            status = OpenCodeEmbeddedGateway.status()
        }
        loaded = true
    }

    fun refreshStatus() {
        status = runCatching { OpenCodeEmbeddedGateway.status() }.getOrDefault("状态未知")
    }

    MiuixScaffoldPage(
        title = "OpenCode 内嵌网关",
        onBack = onBack,
        modifier = Modifier.imePadding(),
    ) {
        if (!loaded) return@MiuixScaffoldPage
        item(key = "status-title") { SmallTitle("状态") }
        item(key = "status") {
            Column {
                Card {
                    BasicComponent(
                        title = "优先使用本地网关",
                        summary = if (enabled) "对话请求发往 127.0.0.1，由随包二进制转发" else "已切换到外部 Zen API",
                        endActions = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { on ->
                                    enabled = on
                                    runCatching { OpenCodeEmbeddedGateway.setEnabled(on) }
                                    refreshStatus()
                                },
                            )
                        },
                    )
                    BasicComponent(
                        title = status,
                        summary = readiness.ifBlank { "模型目录就绪状态只表示可列举，不代表上游推理一定成功" },
                        onClick = {
                            busy = true
                            scope.launch {
                                readiness = withContext(Dispatchers.IO) {
                                    runCatching {
                                        OpenCodeEmbeddedGateway.ensureRunning()
                                        OpenCodeEmbeddedGateway.catalogueReadiness()
                                    }.getOrElse { it.message ?: "本地网关不可用" }
                                }
                                busy = false
                                refreshStatus()
                            }
                        },
                    )
                }
                Text(
                    text = if (busy) "正在启动本地网关…" else "点状态行可立即启动并自检；首次启动会自动拉取模型目录",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        item(key = "keys-title") { SmallTitle("上游密钥（可选）") }
        item(key = "keys") {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = zenKey,
                        onValueChange = { zenKey = it },
                        label = "Zen Key",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )
                    TextField(
                        value = goKey,
                        onValueChange = { goKey = it },
                        label = "Go Key",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = proxy,
                        onValueChange = { proxy = it },
                        label = "上游代理（http://host:port，留空跟随系统）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                BasicComponent(
                    title = "保存并重启网关",
                    summary = "密钥只保存在本机配置，不会回显在界面或日志里",
                    endActions = {
                        Icon(Icons.Rounded.Settings, contentDescription = null)
                    },
                    onClick = {
                        busy = true
                        scope.launch {
                            val message = withContext(Dispatchers.IO) {
                                runCatching {
                                    OpenCodeEmbeddedGateway.setUpstreamProxy(proxy)
                                    OpenCodeEmbeddedGateway.setUpstreamKeys(zenKey, goKey)
                                    OpenCodeEmbeddedGateway.setEnabled(enabled)
                                    "已保存"
                                }.getOrElse { it.message ?: "保存失败" }
                            }
                            readiness = message
                            busy = false
                            refreshStatus()
                        }
                    },
                )
            }
        }
    }
}
