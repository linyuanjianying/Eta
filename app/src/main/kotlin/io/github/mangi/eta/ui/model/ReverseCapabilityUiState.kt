package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

/** 逆向能力 / 作战协议设置页的一次性状态快照。 */
@Immutable
data class ReverseCapabilityUiState(
    /** 逆向能力总开关；缺省开启，保留已可用的技能体验。 */
    val enabled: Boolean = true,
    /** 当前安装技能总数。 */
    val totalSkillCount: Int = 0,
    /** 属于逆向集合的技能数；关闭总开关时这些技能被屏蔽。 */
    val reverseSkillCount: Int = 0,
    /** 当前生效的协议正文（可能为空）。 */
    val protocolContent: String = "",
    /** 协议原文的 SHA-256；空协议为空串。 */
    val protocolRevision: String = "",
    /** 协议原始字符数（截断前）。 */
    val protocolCharCount: Int = 0,
    /** 协议是否超过注入上限被截断。 */
    val protocolTruncated: Boolean = false,
    /** 协议是否只有空白或不存在。 */
    val protocolEmpty: Boolean = true,
    /** 页面是否仍在读取磁盘与技能索引。 */
    val loading: Boolean = true,
    /** 最近一次保存/清空的结果提示；null 表示没有。 */
    val message: String? = null,
)
