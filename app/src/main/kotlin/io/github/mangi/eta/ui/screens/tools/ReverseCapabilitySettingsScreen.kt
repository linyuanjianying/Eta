package io.github.mangi.eta.ui.screens.tools

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 * 页面上只有一个总开关和一行技能统计：开关控制逆向技能是否出现在索引/技能读取里，
 * 同时决定已有的作战协议正文是否注入（注入接线在 `ReverseCapabilityRuntime` 与
 * `AgentRuntimeRunExecutor`）。作战协议正文不再由本页编辑，运行时若存在协议文件仍照常注入。
 */
@Composable
fun ReverseCapabilitySettingsScreen(
    store: ReverseCapabilitySettingsStore,
    onBack: () -> Unit,
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
    val skillsGroup = stringResource(R.string.reverse_capability_skills_group)
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
            }
        }
    }
}
