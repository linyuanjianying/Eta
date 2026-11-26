package io.github.mangi.eta.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.DialogActions
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Page-owned draft, separate from the preferences until Save succeeds. */
internal class RequestCompactionSettingsDraft(enabled: Boolean, interval: String) {
    var enabled by mutableStateOf(enabled)
    val interval = TextFieldState(initialText = interval)

    companion object {
        val Saver = listSaver<RequestCompactionSettingsDraft?, Any>(
            save = { draft ->
                if (draft == null) emptyList() else listOf(draft.enabled, draft.interval.text.toString())
            },
            restore = { values ->
                if (values.isEmpty()) null else RequestCompactionSettingsDraft(
                    enabled = values[0] as Boolean,
                    interval = values[1] as String,
                )
            },
        )
    }
}

@Composable
internal fun RequestCompactionSettingsDialog(
    show: Boolean,
    draft: RequestCompactionSettingsDraft?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    onSave: (RequestCompactionSettingsDraft, Int) -> Unit,
) {
    // Keep this dialog composed, and keep the closing draft until onDismissFinished.
    GlassDialog(
        show = show,
        backdrop = currentGlassOverlayBackdrop(),
        title = stringResource(R.string.settings_request_compaction),
        onDismissRequest = { if (!saving) onDismiss() },
        onDismissFinished = onDismissFinished,
    ) {
        if (draft != null) {
            val interval = parseRequestCompactionIntervalInput(draft.interval.text.toString())
            val invalidMessage = stringResource(
                R.string.settings_request_compaction_interval_invalid,
                Int.MAX_VALUE,
            )
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_request_compaction_enabled),
                        checked = draft.enabled,
                        onCheckedChange = { draft.enabled = it },
                        enabled = !saving,
                    )
                    GlassTextField(
                        state = draft.interval,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        label = stringResource(R.string.settings_request_compaction_interval),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        enabled = !saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { if (interval == null) error(invalidMessage) },
                    )
                    Text(
                        text = if (interval == null) invalidMessage else {
                            stringResource(R.string.settings_request_compaction_interval_hint)
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (interval == null) {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    RequestCompactionHint(stringResource(R.string.settings_request_compaction_counting_hint))
                    RequestCompactionHint(stringResource(R.string.settings_request_compaction_behavior_hint))
                    RequestCompactionHint(stringResource(R.string.settings_request_compaction_threshold_hint))
                    RequestCompactionHint(stringResource(R.string.settings_request_compaction_next_run_hint))
                }
                DialogActions(
                    confirmText = stringResource(R.string.action_save),
                    confirmEnabled = show && !saving && interval != null,
                    cancelEnabled = !saving,
                    onCancel = onDismiss,
                    onConfirm = {
                        // Read the field directly, including the latest IME edit, not an async draft copy.
                        if (show && !saving) {
                            parseRequestCompactionIntervalInput(draft.interval.text.toString())?.let { value ->
                                onSave(draft, value)
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun RequestCompactionHint(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
