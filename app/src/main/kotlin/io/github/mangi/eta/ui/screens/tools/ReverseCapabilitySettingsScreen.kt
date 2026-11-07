package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.app.ReverseCapabilitySettingsStore
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 逆向能力设置页（独立页，纯 UI 接线到 [ReverseCapabilitySettingsStore]）。
 *
 * 同一个开关控制三件事：逆向技能是否出现在索引/技能读取里、作战协议是否注入
 * （注入接线在 `ReverseCapabilityRuntime` 与 `AgentRuntimeRunExecutor`）、
 * 以及设置页里的技能计数展示。
 */
@Composable
fun ReverseCapabilitySettingsScreen(
    store: ReverseCapabilitySettingsStore,
    onBack: () -> Unit,
    onOpenProtocolEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by store.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); store.consumeMessage() }
    }
    LaunchedEffect(Unit) { store.refresh() }
    val title = stringResource(R.string.reverse_capability_title)
    val summary = stringResource(R.string.reverse_capability_summary)
    val skillSummary = if (state.enabled) {
        stringResource(
            R.string.reverse_capability_skills_on,
            state.reverseSkillCount,
            state.totalSkillCount,
        )
    } else {
        stringResource(
            R.string.reverse_capability_skills_off,
            state.reverseSkillCount,
            state.totalSkillCount,
        )
    }
    val protocolSummary = if (state.protocolEmpty) {
        stringResource(R.string.reverse_capability_protocol_empty)
    } else {
        stringResource(
            R.string.reverse_capability_protocol_status,
            state.protocolCharCount,
            state.protocolRevision.take(8),
        )
    }
    val protocolNote = stringResource(R.string.reverse_capability_protocol_note)
    val truncatedNote = if (state.protocolTruncated) {
        stringResource(R.string.reverse_capability_protocol_truncated, store.maxProtocolChars)
    } else {
        null
    }
    val editorLabel = stringResource(R.string.reverse_capability_open_editor)
    val skillsGroup = stringResource(R.string.reverse_capability_skills_group)
    val protocolGroup = stringResource(R.string.reverse_capability_protocol_group)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(title = title, onBack = onBack, modifier = modifier) {
                Group(title = skillsGroup) {
                    CouiSwitchPreference(
                        title = title,
                        summary = summary,
                        checked = state.enabled,
                        onCheckedChange = store::setEnabled,
                    )
                    Divider()
                    CouiPreference(title = skillsGroup, summary = skillSummary)
                }
                Group(title = protocolGroup) {
                    CouiPreference(
                        title = editorLabel,
                        summary = protocolSummary,
                        value = if (state.enabled) null else stringResource(R.string.reverse_capability_summary),
                        enabled = true,
                        onClick = onOpenProtocolEditor,
                    )
                    Divider()
                    CouiPreference(title = protocolGroup, summary = protocolNote)
                    if (truncatedNote != null) {
                        Divider()
                        CouiPreference(title = protocolGroup, summary = truncatedNote)
                    }
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(title = title, onBack = onBack, modifier = modifier) {
                item(key = "reverse-switch") {
                    SmallTitle(skillsGroup)
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SwitchPreference(
                            title = title,
                            summary = summary,
                            checked = state.enabled,
                            onCheckedChange = store::setEnabled,
                        )
                        ArrowPreference(
                            title = skillsGroup,
                            summary = skillSummary,
                            onClick = null,
                        )
                    }
                }
                item(key = "reverse-protocol") {
                    SmallTitle(protocolGroup)
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = editorLabel,
                            summary = protocolSummary,
                            onClick = onOpenProtocolEditor,
                        )
                        ArrowPreference(
                            title = protocolGroup,
                            summary = truncatedNote?.let { "$protocolNote $it" } ?: protocolNote,
                            onClick = null,
                        )
                    }
                }
            }
        }
    }
}
