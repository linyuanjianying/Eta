package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.model.catalog.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.catalog.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.catalog.AgentTerminalToolCatalog
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray

/** Exact supplier-instance ID and Model.modelId API string, never a display name. */
internal data class RequestCompactionTarget(val providerId: String, val modelId: String)

/**
 * 模块配置中枢。
 *
 * - Hook 进程（system_server / SystemUI / Google / 识屏与系统记忆等）在模块加载时调用
 *   [attachRemote]，缓存框架提供的只读 [SharedPreferences]；之后所有拦截回调用 [isEnabled]
 *   读取当前进程持有的 remote preferences。
 * - Eta Runtime 自己消费的开关保存在 App 私有配置中，不依赖 Xposed Service。
 * - Hook 消费的开关通过 [remotePreferencesForUi] 写入 RemotePreferences；
 *   XposedService 未就绪时不提供本地假 fallback。
 *
 * 基于 libxposed API 102 的 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * 与 service 102 的 [XposedService.getRemotePreferences]，两端共用同一 group。
 */
internal object Prefs {

    /** 远程配置组名，UI 写入与 Hook 读取必须一致。 */
    const val GROUP = "eta_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    const val DEFAULT_REQUEST_COMPACTION_INTERVAL = 95
    private const val MAX_REQUEST_COMPACTION_TARGET_ID_LENGTH = 4096
    // Each UTF-16 code unit may be JSON-escaped as six characters, plus the object keys.
    private const val MAX_REQUEST_COMPACTION_TARGET_JSON_LENGTH = 12 * MAX_REQUEST_COMPACTION_TARGET_ID_LENGTH + 128
    private const val REQUEST_COMPACTION_NO_TARGET_JSON = "{\"version\":1,\"target\":null}"

    /** 所有功能开关 key。默认值按功能风险独立定义。 */
    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"
        // 兼容旧版布尔协议；新 UI 不再写入，缺少三态配置时 true 仍表示 Gemini。
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_DEFAULT_REASONING_EFFORT = "agent_default_reasoning_effort"
        const val AGENT_AUTO_COMPACTION_ENABLED = "agent_auto_compaction_enabled"
        const val AGENT_REQUEST_COMPACTION_ENABLED = "agent_request_compaction_enabled"
        const val AGENT_REQUEST_COMPACTION_INTERVAL = "agent_request_compaction_interval"
        const val AGENT_REQUEST_COMPACTION_TARGET_JSON = "agent_request_compaction_target_json"
        const val AGENT_SUBAGENT_ENABLED = "agent_subagent_enabled"
        const val AGENT_VIRTUAL_DISPLAY_ENABLED = "agent_virtual_display_enabled"
        const val AGENT_KEEP_SCREEN_ON_ENABLED = "agent_keep_screen_on_enabled"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"
        const val AGENT_DISABLED_TOOLS_JSON = "agent_disabled_tools_json"

        /** 全部布尔开关及其默认值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            ASSISTANT_AUTO_CONFIG to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_COMPACTION_ENABLED to true,
            AGENT_REQUEST_COMPACTION_ENABLED to false,
            AGENT_SUBAGENT_ENABLED to true,
            AGENT_VIRTUAL_DISPLAY_ENABLED to true,
            AGENT_KEEP_SCREEN_ON_ENABLED to true,
        )

        /** 由 Eta Runtime 最终裁决、不要求 Xposed 框架在线的开关。 */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_THINKING_ENABLED,
            AGENT_AUTO_COMPACTION_ENABLED,
            AGENT_REQUEST_COMPACTION_ENABLED,
            AGENT_SUBAGENT_ENABLED,
            AGENT_VIRTUAL_DISPLAY_ENABLED,
            AGENT_KEEP_SCREEN_ON_ENABLED,
        )
    }

    /** Hook 进程缓存的只读 remote preferences，由 ModuleMain 在 onModuleLoaded 注入。 */
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** App 进程调用：初始化不依赖 Xposed Service 的 Agent 配置。 */
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    ).also(::migrateLegacyGroupSwitchesIfNeeded)
                }
            }
        }
    }

    /** Hook 进程调用：缓存框架提供的只读 SharedPreferences。 */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /** Hook 进程监听框架下发的配置变化；listener 必须由调用方在进程生命周期内强引用。 */
    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val preferences = remote ?: return false
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /**
     * 读取布尔开关。remote 不可用（框架未注入或调用失败）时回退各功能自己的默认值；
     * 默认值与设置页展示保持一致。
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        return remote?.getString(key, "") ?: ""
    }

    fun defaultReasoningEffort(
        preferences: SharedPreferences? = localAgent ?: remote,
    ): ReasoningEffort {
        val raw = runCatching {
            preferences?.getString(Keys.AGENT_DEFAULT_REASONING_EFFORT, null)
        }.getOrNull()
        val parsed = ReasoningEffort.fromWireValue(raw)
        return if (parsed != null && parsed != ReasoningEffort.OFF) parsed else ReasoningEffort.DEFAULT
    }

    fun requestCompactionInterval(
        preferences: SharedPreferences? = localAgent ?: remote,
    ): Int = runCatching {
        preferences?.getInt(Keys.AGENT_REQUEST_COMPACTION_INTERVAL, DEFAULT_REQUEST_COMPACTION_INTERVAL)
    }.getOrNull()?.takeIf { it > 0 } ?: DEFAULT_REQUEST_COMPACTION_INTERVAL

    /** Missing, corrupt or incorrectly typed targets never match any provider/model. */
    fun requestCompactionTarget(
        preferences: SharedPreferences? = localAgent ?: remote,
    ): RequestCompactionTarget? = runCatching {
        val raw = preferences?.getString(Keys.AGENT_REQUEST_COMPACTION_TARGET_JSON, null)
            ?: return null
        if (raw.isBlank() || raw.length > MAX_REQUEST_COMPACTION_TARGET_JSON_LENGTH) return null
        val json = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        if (json["target"] == JsonNull) return null
        fun string(key: String): String? = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val target = RequestCompactionTarget(
            providerId = string("providerId") ?: return null,
            modelId = string("modelId") ?: return null,
        )
        target.takeIf(::isValidRequestCompactionTarget)
    }.getOrNull()

    fun requestCompactionTargetMatches(
        providerId: String,
        modelId: String,
        preferences: SharedPreferences? = localAgent ?: remote,
    ): Boolean = requestCompactionTarget(preferences)?.let {
        it.providerId == providerId && it.modelId == modelId
    } ?: false

    private fun isValidRequestCompactionTarget(target: RequestCompactionTarget): Boolean =
        listOf(target.providerId, target.modelId).all {
            it.isNotBlank() && it.length <= MAX_REQUEST_COMPACTION_TARGET_ID_LENGTH
        }

    private fun encodeRequestCompactionTarget(target: RequestCompactionTarget?): String = target?.let {
        JsonObject(mapOf("providerId" to JsonPrimitive(it.providerId), "modelId" to JsonPrimitive(it.modelId))).toString()
    } ?: REQUEST_COMPACTION_NO_TARGET_JSON

    private fun SharedPreferences.Editor.putRequestCompactionTarget(encoded: String): SharedPreferences.Editor =
        putString(Keys.AGENT_REQUEST_COMPACTION_TARGET_JSON, encoded)

    /**
     * 本地提交成功即视为保存成功；Hook 配置仅作尽力同步，不影响本地事实源。
     * 省略 target 时不改动现有目标键，保持旧调用对“从未配置”的兼容性；显式传 null
     * 写入持久化 tombstone，避免远端旧值在重试或 reconcile 时复活。
     */
    fun setRequestCompactionSettings(
        enabled: Boolean,
        interval: Int,
        service: XposedService? = null,
    ): Boolean {
        if (interval <= 0) return false
        val local = localAgent ?: return false
        val encodedTarget = runCatching {
            if (local.contains(Keys.AGENT_REQUEST_COMPACTION_TARGET_JSON)) {
                encodeRequestCompactionTarget(requestCompactionTarget(local))
            } else {
                null
            }
        }.getOrElse { return false }
        return setRequestCompactionSettingsInternal(
            enabled = enabled,
            interval = interval,
            service = service,
            encodedTarget = encodedTarget,
        )
    }

    fun setRequestCompactionSettings(
        enabled: Boolean,
        interval: Int,
        service: XposedService? = null,
        target: RequestCompactionTarget?,
    ): Boolean {
        if (target != null && !isValidRequestCompactionTarget(target)) return false
        return setRequestCompactionSettingsInternal(
            enabled = enabled,
            interval = interval,
            service = service,
            encodedTarget = runCatching { encodeRequestCompactionTarget(target) }.getOrElse { return false },
        )
    }

    private fun setRequestCompactionSettingsInternal(
        enabled: Boolean,
        interval: Int,
        service: XposedService?,
        encodedTarget: String?,
    ): Boolean {
        if (interval <= 0) return false
        val local = localAgent ?: return false
        val committed = synchronized(local) {
            // 只快照这三个键；损坏类型也原样保留，不能用 getAll() 读取其他配置。
            val previous = runCatching {
                listOf(
                    Keys.AGENT_REQUEST_COMPACTION_ENABLED,
                    Keys.AGENT_REQUEST_COMPACTION_INTERVAL,
                    Keys.AGENT_REQUEST_COMPACTION_TARGET_JSON,
                ).associateWith { snapshotPreference(local, it) }
            }.getOrElse { return false }
            val saved = runCatching {
                local.edit()
                    .putBoolean(Keys.AGENT_REQUEST_COMPACTION_ENABLED, enabled)
                    .putInt(Keys.AGENT_REQUEST_COMPACTION_INTERVAL, interval)
                    .apply {
                        if (encodedTarget != null) putRequestCompactionTarget(encodedTarget)
                    }
                    .commit()
            }.getOrDefault(false)
            if (!saved) {
                fun rollbackEditor(): SharedPreferences.Editor = local.edit().also { editor ->
                    previous.forEach { (key, value) -> value.restore(editor, key) }
                }
                // Android commit() 先修改内存，即使返回 false 也必须回滚。
                // 回滚 commit 再次失败仍不能宣称磁盘成功；本次保存始终返回 false。
                runCatching { rollbackEditor().commit() }.onFailure {
                    // 如异常发生在 commitToMemory 前，限一次 apply 尽力恢复内存，不等待磁盘。
                    runCatching { rollbackEditor().apply() }
                }
            }
            saved
        }
        if (committed) {
            runCatching {
                remotePreferencesForUi(service)?.edit()
                    ?.putBoolean(Keys.AGENT_REQUEST_COMPACTION_ENABLED, enabled)
                    ?.putInt(Keys.AGENT_REQUEST_COMPACTION_INTERVAL, interval)
                    ?.apply {
                        if (encodedTarget != null) putRequestCompactionTarget(encodedTarget)
                    }
                    ?.commit()
            }
        }
        return committed
    }

    private data class PreferenceSnapshot(val existed: Boolean, val value: Any? = null) {
        fun restore(editor: SharedPreferences.Editor, key: String) {
            if (!existed) {
                editor.remove(key)
                return
            }
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                else -> error("Unsupported preference value")
            }
        }
    }

    private fun snapshotPreference(preferences: SharedPreferences, key: String): PreferenceSnapshot {
        if (!preferences.contains(key)) return PreferenceSnapshot(existed = false)
        // SharedPreferences 没有按键读取原始值的 API；只探测该键的六种合法存储类型。
        val readers = listOf<() -> Any?>(
            { preferences.getBoolean(key, false) },
            { preferences.getInt(key, 0) },
            { preferences.getLong(key, 0L) },
            { preferences.getFloat(key, 0f) },
            { preferences.getString(key, null) },
            { preferences.getStringSet(key, null)?.toSet() },
        )
        for (read in readers) {
            try {
                return PreferenceSnapshot(existed = true, value = checkNotNull(read()))
            } catch (_: ClassCastException) {
                // 错类型继续探测；其他读取异常在写入前直接中止保存。
            }
        }
        error("Unsupported preference value")
    }

    fun powerAssistantTarget(): PowerAssistantTarget = powerAssistantTarget(remote)

    fun powerAssistantTarget(preferences: SharedPreferences?): PowerAssistantTarget {
        val persistedValue = runCatching {
            preferences?.getString(Keys.POWER_KEY_ASSISTANT_TARGET, null)
        }.getOrNull()
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.POWER_KEY_TAKEOVER)
        val legacyTakeover = runCatching {
            preferences?.getBoolean(Keys.POWER_KEY_TAKEOVER, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return PowerAssistantTarget.resolve(persistedValue, legacyTakeover)
    }

    @Volatile
    private var cachedRemoteService: XposedService? = null

    @Volatile
    private var cachedRemotePrefs: SharedPreferences? = null

    /**
     * 仅从进程内缓存读取已绑定服务的 RemotePreferences，避免 UI 首帧在主线程触发同步 Binder。
     */
    fun cachedRemotePreferencesForUi(service: XposedService?): SharedPreferences? {
        if (service == null) return null
        return cachedRemotePrefs?.takeIf { cachedRemoteService === service }
    }

    /**
     * UI 进程获取可写的 RemotePreferences。
     *
     * [XposedService.getRemotePreferences] 的 commit 会同步等待 binder 提交到 LSPosed
     * 数据库，失败返回 false；service 未就绪时返回 null，让 UI 保持不可写。
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? {
        if (service == null) {
            cachedRemoteService = null
            cachedRemotePrefs = null
            return null
        }
        cachedRemotePrefs?.takeIf { cachedRemoteService === service }?.let { return it }
        return runCatching { service.getRemotePreferences(GROUP) }
            .getOrNull()
            ?.also { resolved ->
                cachedRemoteService = service
                cachedRemotePrefs = resolved
            }
    }

    /** Eta 设置页与 Runtime 使用的本地 Agent 配置，不依赖 LSPosed。 */
    fun localAgentPreferences(): SharedPreferences? = localAgent

    /**
     * 首次升级优先把已有 RemotePreferences 值迁入本地；之后本地值是事实源，并在框架
     * 可用时回写远端，让仍在目标进程中组装请求的 Hook 入口拿到一致的初始配置。
     */
    fun reconcileAgentPreferences(service: XposedService?) {
        val local = localAgent ?: return
        val remotePreferences = remotePreferencesForUi(service) ?: return
        val localEditor = local.edit()
        val remoteEditor = remotePreferences.edit()
        var updateLocal = false
        var updateRemote = false

        Keys.LOCAL_AGENT_KEYS.forEach { key ->
            val default = Keys.BOOLEAN_DEFAULTS.getValue(key)
            when {
                local.contains(key) -> {
                    remoteEditor.putBoolean(key, local.getBoolean(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putBoolean(key, remotePreferences.getBoolean(key, default))
                    updateLocal = true
                }
            }
        }
        // Int 配置不属于 LOCAL_AGENT_KEYS 的布尔协议，独立迁移并校验损坏值。
        when {
            local.contains(Keys.AGENT_REQUEST_COMPACTION_INTERVAL) -> {
                remoteEditor.putInt(Keys.AGENT_REQUEST_COMPACTION_INTERVAL, requestCompactionInterval(local))
                updateRemote = true
            }
            remotePreferences.contains(Keys.AGENT_REQUEST_COMPACTION_INTERVAL) -> {
                localEditor.putInt(Keys.AGENT_REQUEST_COMPACTION_INTERVAL, requestCompactionInterval(remotePreferences))
                updateLocal = true
            }
        }
        // Structured String target is not part of the Boolean protocol; invalid data stays fail-closed.
        when {
            local.contains(Keys.AGENT_REQUEST_COMPACTION_TARGET_JSON) -> {
                remoteEditor.putRequestCompactionTarget(encodeRequestCompactionTarget(requestCompactionTarget(local)))
                updateRemote = true
            }
            remotePreferences.contains(Keys.AGENT_REQUEST_COMPACTION_TARGET_JSON) -> {
                localEditor.putRequestCompactionTarget(encodeRequestCompactionTarget(requestCompactionTarget(remotePreferences)))
                updateLocal = true
            }
        }
        when {
            local.contains(Keys.AGENT_DISABLED_TOOLS_JSON) -> {
                remoteEditor.putString(
                    Keys.AGENT_DISABLED_TOOLS_JSON,
                    local.getString(Keys.AGENT_DISABLED_TOOLS_JSON, "[]"),
                )
                updateRemote = true
            }
            remotePreferences.contains(Keys.AGENT_DISABLED_TOOLS_JSON) -> {
                localEditor.putString(
                    Keys.AGENT_DISABLED_TOOLS_JSON,
                    remotePreferences.getString(Keys.AGENT_DISABLED_TOOLS_JSON, "[]"),
                )
                updateLocal = true
            }
        }
        if (updateLocal) localEditor.commit()
        if (updateRemote) runCatching { remoteEditor.commit() }
    }

    /** 将运行时工具名映射为工具列表中的主开关标识，确保同一能力的不同动作变体受同一开关约束。 */
    fun canonicalToolSwitchId(toolName: String): String = when (toolName) {
        "tap" -> "tap_area"
        "long_press_element" -> "long_press"
        "scroll_element" -> "scroll"
        "input_text", "replace_text", "clear_text", "paste_text" -> "type_text"
        "wait", "wait_for_package" -> "wait_for_text"
        "browser_read", "browser_interact", "browser_screenshot" -> "browser_use"
        "search_coloros_notes" -> "search_notes"
        "search_coloros_memories" -> "search_system_memories"
        else -> PersonalSearchTools.canonical(toolName)
    }

    fun disabledTools(preferences: SharedPreferences? = localAgent ?: remote): Set<String> {
        val raw = runCatching {
            preferences?.getString(Keys.AGENT_DISABLED_TOOLS_JSON, null)
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optString(index).trim()
                    if (item.isNotEmpty()) add(item)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun isToolEnabledInSet(toolName: String, disabled: Set<String>): Boolean {
        if (disabled.isEmpty()) return true
        val canonical = canonicalToolSwitchId(toolName)
        return toolName !in disabled && canonical !in disabled
    }

    fun isToolEnabled(
        toolName: String,
        preferences: SharedPreferences? = localAgent ?: remote,
    ): Boolean = isToolEnabledInSet(toolName, disabledTools(preferences))

    fun setToolEnabled(
        toolName: String,
        enabled: Boolean,
        service: XposedService? = null,
    ): Boolean = setToolsEnabled(listOf(toolName), enabled, service)

    fun setToolsEnabled(
        toolNames: Collection<String>,
        enabled: Boolean,
        service: XposedService? = null,
    ): Boolean {
        val local = localAgent ?: return false
        val updated = disabledTools(local).toMutableSet()
        toolNames.forEach { rawName ->
            val canonical = canonicalToolSwitchId(rawName)
            if (enabled) {
                updated -= rawName
                updated -= canonical
            } else {
                updated += canonical
            }
        }
        val encoded = JSONArray(updated.sorted()).toString()
        val editor = local.edit().putString(Keys.AGENT_DISABLED_TOOLS_JSON, encoded)
        LEGACY_TOOL_GROUP_KEYS.forEach { key ->
            if (local.contains(key) && !local.getBoolean(key, true)) {
                editor.putBoolean(key, true)
            }
        }
        val committed = runCatching { editor.commit() }.getOrDefault(false)
        if (committed && service != null) {
            reconcileAgentPreferences(service)
        }
        return committed
    }

    private fun migrateLegacyGroupSwitchesIfNeeded(local: SharedPreferences) {
        if (local.contains(Keys.AGENT_DISABLED_TOOLS_JSON)) return
        val disabledLegacyGroups = LEGACY_TOOL_GROUP_KEYS.filter { key ->
            local.contains(key) && !local.getBoolean(key, true)
        }
        if (disabledLegacyGroups.isEmpty()) return
        val migratedDisabled = linkedSetOf<String>()
        if (Keys.AGENT_BROWSER_TOOLS in disabledLegacyGroups) {
            migratedDisabled += listOf("web_search", "fetch_url", "browser_use")
        }
        if (Keys.AGENT_TERMINAL_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentTerminalToolCatalog.names
            migratedDisabled += AgentFileToolCatalog.names
            migratedDisabled += "read_image"
        }
        if (Keys.AGENT_DEVICE_DIRECT_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentPhoneToolCatalog.direct
            migratedDisabled += listOf(
                "set_alarm", "set_timer", "device_status", "inspect_app",
                "network_info", "top_memory_apps", "top_storage_apps",
                "media_control", "set_volume",
            )
        }
        if (Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS in disabledLegacyGroups) {
            migratedDisabled += PersonalSearchTools.names
            migratedDisabled += AgentPhoneToolCatalog.reads
            migratedDisabled += listOf(
                "read_personal_item", "summarize_bills", "get_setting", "wifi_credentials",
                "recent_notifications", "search_notification_history", "recent_app_activity",
                "app_usage_summary", "get_current_location", "get_device_environment",
                "list_alarms", "list_active_timers", "search_clipboard_history",
                "get_health_summary", "read_sms_code", "get_logcat", "search_media",
                "search_audio", "search_recordings", "search_files", "search_calendar_events",
                "search_contacts", "search_call_history", "search_messages", "search_downloads",
                "search_notes", "search_coloros_recordings", "search_recording_summaries",
                "search_system_memories", "search_saved_places", "search_personal_orders",
                "search_qq_chat_images", "search_wechat_chat_images",
            )
        }
        if (Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentPhoneToolCatalog.writes
            migratedDisabled += listOf("set_setting", "set_device_state", "app_state_control")
        }
        val editor = local.edit()
            .putString(Keys.AGENT_DISABLED_TOOLS_JSON, JSONArray(migratedDisabled.sorted()).toString())
        disabledLegacyGroups.forEach { key -> editor.putBoolean(key, true) }
        runCatching { editor.commit() }
    }

    private val LEGACY_TOOL_GROUP_KEYS = listOf(
        Keys.AGENT_TERMINAL_TOOLS,
        Keys.AGENT_BROWSER_TOOLS,
        Keys.AGENT_DEVICE_DIRECT_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
    )
}
