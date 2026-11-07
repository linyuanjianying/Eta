package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.app.ReverseCapabilitySettingsStore
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffold
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarTextButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 作战协议编辑页（独立页）。
 *
 * 编辑区只承载**用户自己的文本**：保存即写盘，运行时作为独立 system 消息注入；
 * 本页不解析、不执行、不导入任何技能或脚本内容。
 */
@Composable
fun CombatProtocolEditorScreen(
    store: ReverseCapabilitySettingsStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by store.state.collectAsState()
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            if (message.startsWith("作战协议已清空")) draft = ""
            store.consumeMessage()
        }
    }
    LaunchedEffect(Unit) { store.refresh() }
    LaunchedEffect(state.protocolContent, state.loading) {
        if (draft == null && !state.loading) draft = state.protocolContent
    }
    val current = draft.orEmpty()
    val dirty = draft != null && draft != state.protocolContent
    val title = stringResource(R.string.reverse_capability_editor_title)
    val hint = stringResource(R.string.reverse_capability_editor_hint)
    val saveLabel = stringResource(R.string.reverse_capability_editor_save)
    val clearLabel = stringResource(R.string.reverse_capability_editor_clear)
    val counter = stringResource(
        R.string.reverse_capability_editor_counter,
        current.length,
        store.maxProtocolChars,
    )
    val emptyNote = if (current.isBlank()) {
        stringResource(R.string.reverse_capability_editor_empty_draft)
    } else {
        null
    }

    val commit = {
        store.saveProtocol(current)
        draft = current
    }
    val requestClear = { confirmClear = true }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffold(
                title = title,
                onBack = onBack,
                modifier = modifier,
                actions = {
                    CouiTopAppBarTextButton(
                        text = saveLabel,
                        onClick = commit,
                        enabled = dirty,
                    )
                },
            ) { _, _, _, sidePadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = sidePadding)
                        .padding(top = 12.dp, bottom = 24.dp),
                ) {
                    Group(title = title) {
                        CouiPreference(title = title, summary = hint)
                        Divider()
                        CouiPreference(
                            title = title,
                            summary = counter,
                            onClick = null,
                        )
                        if (emptyNote != null) {
                            Divider()
                            CouiPreference(title = title, summary = emptyNote, onClick = null)
                        }
                    }
                    ProtocolTextField(
                        value = current,
                        onValueChange = { draft = it },
                        placeholder = hint,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    CouiPreference(
                        title = clearLabel,
                        onClick = requestClear,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = title,
                onBack = onBack,
                modifier = modifier,
                actions = {
                    TextButton(text = saveLabel, onClick = commit, enabled = dirty)
                },
            ) {
                item(key = "editor-hint") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Text(
                            text = hint,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(12.dp),
                        )
                        Text(
                            text = counter,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        )
                    }
                }
                item(key = "editor-field") {
                    Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ProtocolTextField(
                            value = current,
                            onValueChange = { draft = it },
                            placeholder = hint,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item(key = "editor-clear") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp, bottom = 24.dp),
                    ) {
                        TextButton(
                            text = clearLabel,
                            onClick = requestClear,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        CouiConfirmDialog(
            visible = true,
            title = stringResource(R.string.reverse_capability_editor_clear_confirm_title),
            message = stringResource(R.string.reverse_capability_editor_clear_confirm_summary),
            confirmText = clearLabel,
            destructive = true,
            onConfirm = {
                confirmClear = false
                store.clearProtocol()
            },
            onDismissRequest = { confirmClear = false },
        )
    }
}

@Composable
private fun ProtocolTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val coui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiColors = CouiPageDefaults.themeColors(CouiTheme.isDark)
    val textStyle = if (coui) {
        MiuixTheme.textStyles.body2.copy(color = couiColors.title)
    } else {
        MiuixTheme.textStyles.body2
    }
    val foregroundColor = if (coui) couiColors.title else MiuixTheme.colorScheme.onBackground
    val placeholderColor = if (coui) couiColors.summary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val cursorColor = if (coui) couiColors.accent else MiuixTheme.colorScheme.primary
    Box(modifier = modifier.heightIn(min = 220.dp)) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = textStyle,
                color = placeholderColor,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = textStyle.copy(color = foregroundColor),
            cursorBrush = SolidColor(cursorColor),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
