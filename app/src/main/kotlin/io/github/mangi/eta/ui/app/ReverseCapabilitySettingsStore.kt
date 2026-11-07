package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.skill.ReverseCapabilityRuntime
import io.github.mangi.eta.agent.skill.ReverseSkillCatalog
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.repository.AgentCombatProtocolRepository
import io.github.mangi.eta.data.repository.AgentCombatProtocolStore
import io.github.mangi.eta.ui.model.ReverseCapabilityUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 逆向能力设置页的 Store。
 *
 * 只做三件事：读/写总开关、读/写作战协议文本、统计被开关影响的技能数。
 * 协议始终被当作数据：这里只保存字符串，不解析、不执行、也不交给技能加载器。
 */
class ReverseCapabilitySettingsStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(ReverseCapabilityUiState())
    val state: StateFlow<ReverseCapabilityUiState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) { loadState() }
            _state.value = snapshot
        }
    }

    fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                ReverseCapabilityRuntime.setEnabled(appContext, enabled)
            }
            val snapshot = loadState().copy(
                message = if (written) null else "开关写入失败，当前生效值未改变",
            )
            _state.value = snapshot
        }
    }

    /** 保存协议正文；超限或写入失败时把错误放进 [ReverseCapabilityUiState.message]。 */
    fun saveProtocol(content: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AgentCombatProtocolRepository.write(content) }
            }
            _state.value = withContext(Dispatchers.IO) { loadState() }.copy(
                message = result.fold(
                    onSuccess = { "作战协议已保存" },
                    onFailure = { error -> "保存失败：${error.message ?: "未知错误"}" },
                ),
            )
        }
    }

    fun clearProtocol() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AgentCombatProtocolRepository.clear() }
            }
            _state.value = withContext(Dispatchers.IO) { loadState() }.copy(
                message = result.fold(
                    onSuccess = { "作战协议已清空（原文件已备份）" },
                    onFailure = { error -> "清空失败：${error.message ?: "未知错误"}" },
                ),
            )
        }
    }

    fun consumeMessage() {
        if (_state.value.message != null) _state.value = _state.value.copy(message = null)
    }

    private fun loadState(): ReverseCapabilityUiState {
        val enabled = ReverseCapabilityRuntime.isEnabled(appContext)
        val entries = runCatching {
            SkillRuntime.createIndexService(appContext).listSkillsForManagement()
        }.getOrDefault(emptyList())
        val protocol = runCatching { AgentCombatProtocolRepository.refresh() }
            .getOrDefault(AgentCombatProtocolRepository.snapshot())
        return ReverseCapabilityUiState(
            enabled = enabled,
            totalSkillCount = entries.size,
            reverseSkillCount = entries.count { ReverseSkillCatalog.isReverse(it) },
            protocolContent = protocol.content,
            protocolRevision = protocol.revision,
            protocolCharCount = protocol.charCount,
            protocolTruncated = protocol.truncated,
            protocolEmpty = !protocol.available,
            loading = false,
        )
    }

    /** 注入上限，供页面显示占用比例。 */
    val maxProtocolChars: Int get() = AgentCombatProtocolStore.MAX_CHARS
}
