package io.github.mangi.eta.agent.tool

/**
 * 工具的人话名字与功能分类。
 *
 * 工具 ID 是英文缩写（`app_usage_summary`、`grep_files`），模型认识，用户不认识。
 * 这里给每个工具一个中文名与一个功能域，界面按功能域收纳，搜索也匹配中文名。
 * 未收录的工具回退成原始 ID + "其他"，不会因此消失。
 */
internal object AgentToolNaming {
    data class Face(val label: String, val category: String)

    const val CATEGORY_OTHER = "其他"
    const val CATEGORY_MCP = "MCP 工具"

    /** 分类的显示顺序：常用在前。 */
    val categoryOrder: List<String> = listOf(
        "屏幕与界面",
        "应用与设备",
        "文件与终端",
        "搜索与网页",
        "个人数据",
        "通知与用量",
        "日程与提醒",
        "账单与出行",
        "闹钟与计时",
        "记忆与人格",
        "技能与扩展",
        "任务与自动化",
        "诊断与日志",
        CATEGORY_MCP,
        CATEGORY_OTHER,
    )

    fun face(name: String): Face =
        table[name]?.let { entry ->
            val parts = entry.split('|')
            Face(label = parts.getOrElse(1) { name }, category = parts[0])
        } ?: mcpFace(name) ?: Face(label = name, category = CATEGORY_OTHER)

    /**
     * MCP 服务器带来的工具（`mt_apk_workspace_open_0659234f` 这种）名字由服务端决定，
     * 没法逐个维护中文名：这里去掉尾部哈希、把下划线换成空格、按前缀归到 MCP 分类。
     */
    private fun mcpFace(name: String): Face? {
        val base = name.replace(Regex("_[0-9a-f]{8}$"), "")
        if (!base.startsWith("mt_") && !name.contains("__")) return null
        val readable = base.removePrefix("mt_").replace('_', ' ').trim()
        return Face(label = "MT · " + readable.replaceFirstChar { it.uppercase() }, category = CATEGORY_MCP)
    }

    private val table: Map<String, String> = mapOf(
        // 屏幕与界面
        "get_current_context" to "屏幕与界面|读当前时间与位置",
        "search_apps" to "屏幕与界面|查找已装应用",
        "launch_app" to "屏幕与界面|启动应用（真机）",
        "lingscreen_open_app" to "屏幕与界面|启动应用（灵屏自动化）",
        "open_uri" to "屏幕与界面|把链接交给其他应用",
        "observe_screen" to "屏幕与界面|观察屏幕",
        "tap" to "屏幕与界面|按坐标点击",
        "tap_area" to "屏幕与界面|点击区域",
        "tap_element" to "屏幕与界面|点击元素",
        "long_press" to "屏幕与界面|长按（坐标）",
        "long_press_element" to "屏幕与界面|长按（元素）",
        "swipe" to "屏幕与界面|滑动",
        "scroll" to "屏幕与界面|滚动",
        "scroll_element" to "屏幕与界面|滚动指定列表",
        "type_text" to "屏幕与界面|输入文字",
        "input_text" to "屏幕与界面|写入选中的输入框",
        "replace_text" to "屏幕与界面|替换输入框内容",
        "clear_text" to "屏幕与界面|清空输入框",
        "set_clipboard" to "屏幕与界面|写剪贴板",
        "get_clipboard" to "屏幕与界面|读剪贴板",
        "paste_text" to "屏幕与界面|粘贴到输入框",
        "wait" to "屏幕与界面|等一会",
        "wait_for_text" to "屏幕与界面|等文字出现",
        "wait_for_package" to "屏幕与界面|等应用到前台",
        "open_system_panel" to "屏幕与界面|开通知栏或快捷开关",
        "press_key" to "屏幕与界面|按系统键",
        "virtual_screen" to "屏幕与界面|灵屏控制",
        "inspect_app" to "屏幕与界面|查看应用信息",
        "app_state_control" to "屏幕与界面|停用或冻结应用",

        // 应用与设备
        "device_status" to "应用与设备|电池内存存储",
        "network_info" to "应用与设备|当前网络",
        "get_setting" to "应用与设备|读系统设置",
        "set_setting" to "应用与设备|改系统设置",
        "set_device_state" to "应用与设备|开关 WLAN 蓝牙等",
        "top_memory_apps" to "应用与设备|内存占用排行",
        "top_storage_apps" to "应用与设备|存储占用排行",
        "wifi_credentials" to "应用与设备|已存 WiFi 密码",
        "get_device_environment" to "应用与设备|锁屏勿扰外放状态",
        "get_current_location" to "应用与设备|当前位置",
        "media_control" to "应用与设备|播放暂停切歌",
        "set_volume" to "应用与设备|调音量",
        "get_health_summary" to "应用与设备|步数睡眠心率",

        // 通知与用量
        "recent_notifications" to "通知与用量|当前通知",
        "search_notification_history" to "通知与用量|历史通知",
        "recent_app_activity" to "通知与用量|最近打开的应用",
        "app_usage_summary" to "通知与用量|应用前台时长",
        "app_usage_summary " to "通知与用量|应用前台时长",
        "read_sms_code" to "通知与用量|短信验证码",

        // 闹钟与计时
        "set_alarm" to "闹钟与计时|设闹钟",
        "set_timer" to "闹钟与计时|设计时器",
        "list_alarms" to "闹钟与计时|现有闹钟",
        "list_active_timers" to "闹钟与计时|正在跑的计时器",

        // 文件与终端
        "terminal" to "文件与终端|执行命令",
        "terminal_job" to "文件与终端|后台跑命令",
        "terminal_daemon" to "文件与终端|常驻后台服务",
        "read_file" to "文件与终端|读文件",
        "write_file" to "文件与终端|写文件",
        "edit_file" to "文件与终端|改文件片段",
        "stat_file" to "文件与终端|文件属性",
        "list_directory" to "文件与终端|列目录",
        "glob_files" to "文件与终端|按通配找文件",
        "grep_files" to "文件与终端|全文搜索文件",
        "read_image" to "文件与终端|看图",

        // 搜索与网页
        "web_search" to "搜索与网页|网页搜索",
        "fetch_url" to "搜索与网页|抓网页正文",
        "browser_use" to "搜索与网页|操作浏览器",
        "search_files" to "搜索与网页|找共享文件",
        "search_downloads" to "搜索与网页|下载记录",

        // 个人数据
        "search_calendar_events" to "个人数据|查日程",
        "search_contacts" to "个人数据|查联系人",
        "search_call_history" to "个人数据|查通话记录",
        "search_messages" to "个人数据|查短信",
        "search_notes" to "个人数据|查便签",
        "search_media" to "个人数据|查相册图片",
        "search_audio" to "个人数据|查本地音乐",
        "search_recordings" to "个人数据|查录音",
        "search_coloros_recordings" to "个人数据|查系统录音",
        "search_recording_summaries" to "个人数据|查录音摘要",
        "search_system_memories" to "个人数据|查系统记忆",
        "search_saved_places" to "个人数据|查常去地点",
        "search_personal_orders" to "个人数据|查订单",
        "search_clipboard_history" to "个人数据|查剪贴板历史",
        "search_qq_chat_images" to "个人数据|查 QQ 图片",
        "search_wechat_chat_images" to "个人数据|查微信图片",

        // 记忆与人格
        "memory_get" to "记忆与人格|读长期记忆",
        "memory_write" to "记忆与人格|写长期记忆",
        "character_memory_get" to "记忆与人格|读角色记忆",
        "character_memory_write" to "记忆与人格|写角色记忆",

        // 技能与扩展
        "skills_list" to "技能与扩展|列已装技能",
        "skills_read" to "技能与扩展|读技能说明",
        "skills_read_resource" to "技能与扩展|读技能附带文件",
        "skills_list_curated" to "技能与扩展|看官方技能库",
        "skills_manage" to "技能与扩展|新建或改技能",
        "skills_inspect_github" to "技能与扩展|看 GitHub 技能库",
        "skills_install_from_github" to "技能与扩展|装 GitHub 技能",

        // 任务与自动化
        "tasks_triggers" to "任务与自动化|可用的触发方式",
        "tasks_list" to "任务与自动化|列任务",
        "tasks_create" to "任务与自动化|建任务",
        "tasks_update" to "任务与自动化|改任务",
        "tasks_delete" to "任务与自动化|删任务",
        "tasks_run" to "任务与自动化|立刻跑一次",
        "tasks_cancel" to "任务与自动化|取消运行",
        "tasks_history" to "任务与自动化|任务运行记录",


        // 日程与提醒（补齐：之前这 33 个全都落到"其他"）
        "list_calendars" to "日程与提醒|列可用日历",
        "read_calendar_event" to "日程与提醒|读日程详情",
        "create_calendar_event" to "日程与提醒|新建日程",
        "create_calendar_events" to "日程与提醒|批量建日程",
        "update_calendar_event" to "日程与提醒|改日程",
        "delete_calendar_event" to "日程与提醒|删日程",
        "search_calendar_todos" to "日程与提醒|查日程待办",
        "search_todos" to "日程与提醒|查待办",
        "create_note" to "日程与提醒|建便签",
        "read_note" to "日程与提醒|读便签",
        "delete_note" to "日程与提醒|删便签",
        "update_alarm_time" to "日程与提醒|改闹钟时间",
        "set_alarm_enabled" to "日程与提醒|开关闹钟",
        "delete_alarm" to "日程与提醒|删闹钟",

        // 账单与出行
        "search_bills" to "账单与出行|查账单",
        "summarize_bills" to "账单与出行|账单汇总",
        "search_flights" to "账单与出行|查航班",
        "search_trains" to "账单与出行|查车次",
        "search_hotels" to "账单与出行|查酒店",
        "search_daily_events" to "账单与出行|查生活事件",
        "read_personal_item" to "账单与出行|读个人数据详情",
        "search_memory_collections" to "账单与出行|查记忆合集",

        // 设备开关（原来只映射了 set_device_state，读侧全落空）
        "get_device_state" to "应用与设备|读系统开关",
        "get_display_state" to "应用与设备|读亮度与旋转",
        "set_brightness" to "应用与设备|调亮度",
        "set_screen_timeout" to "应用与设备|改息屏时间",
        "get_sound_state" to "应用与设备|读音量与勿扰",
        "set_sound_mode" to "应用与设备|改响铃模式",
        "set_do_not_disturb" to "应用与设备|设勿扰",
        "get_hotspot" to "应用与设备|热点状态",
        "set_hotspot" to "应用与设备|开关热点",
        "get_flashlight" to "应用与设备|手电状态",
        "set_flashlight" to "应用与设备|开关手电",

        // 诊断与日志
        "get_logcat" to "诊断与日志|读系统日志",
    )
}
