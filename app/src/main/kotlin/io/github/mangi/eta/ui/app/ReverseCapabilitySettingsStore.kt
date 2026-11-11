package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.skill.ReverseCapabilityRuntime
import io.github.mangi.eta.agent.skill.ReverseSkillCatalog
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.ui.model.ReverseCapabilityUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 逆向能力设置页的 Store。
 *
 * 只做两件事：读/写逆向能力总开关、统计被开关影响的技能数。
 * 作战协议正文不再由界面编辑（没有任何写入口径），运行时仍按开关决定是否注入既有文件。
 */
class ReverseCapabilitySettingsStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(ReverseCapabilityUiState())
    val state: StateFlow<ReverseCapabilityUiState> = _state.asStateFlow()
    private val operationMutex = Mutex()
    private var stateRevision = 0L

    fun refresh() {
        val revision = stateRevision
        scope.launch {
            operationMutex.withLock {
                val snapshot = withContext(Dispatchers.IO) { loadState() }
                if (revision == stateRevision) _state.value = snapshot
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        val revision = ++stateRevision
        _state.value = _state.value.copy(enabled = enabled)
        scope.launch {
            operationMutex.withLock {
                if (revision != stateRevision) return@withLock
                val snapshot = withContext(Dispatchers.IO) {
                    val written = ReverseCapabilityRuntime.setEnabled(appContext, enabled)
                    loadState().copy(
                        message = if (written) null else "开关写入失败，已重新读取当前生效状态",
                    )
                }
                if (revision == stateRevision) _state.value = snapshot
            }
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
        return ReverseCapabilityUiState(
            enabled = enabled,
            totalSkillCount = entries.size,
            reverseSkillCount = entries.count { ReverseSkillCatalog.isReverse(it) },
            loading = false,
        )
    }
}
