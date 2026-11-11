package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

/** 逆向能力设置页的一次性状态快照：只有总开关状态、技能计数和写入结果提示。 */
@Immutable
data class ReverseCapabilityUiState(
    /** 逆向能力总开关；缺省开启，保留已可用的技能体验。 */
    val enabled: Boolean = true,
    /** 当前安装技能总数。 */
    val totalSkillCount: Int = 0,
    /** 属于逆向集合的技能数；关闭总开关时这些技能被屏蔽。 */
    val reverseSkillCount: Int = 0,
    /** 页面是否仍在读取磁盘与技能索引。 */
    val loading: Boolean = true,
    /** 最近一次开关写入的结果提示；null 表示没有。 */
    val message: String? = null,
)
