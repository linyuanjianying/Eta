package io.github.mangi.eta.agent.skill

import android.content.Context
import io.github.mangi.eta.data.repository.AgentCombatProtocolRepository
import io.github.mangi.eta.data.repository.AgentCombatProtocolSnapshot
import io.github.mangi.eta.data.repository.ReverseCapabilitySettings

/**
 * 一次 run 的「逆向能力」解析结果。
 *
 * Runtime 在准备阶段算一次并在整轮内保持不变：中途改开关只影响下一轮，
 * 避免同一轮内系统提示抖动把 prompt 缓存全部作废。
 */
data class ReverseCapabilityDecision(
    /** 总开关是否开启。关闭时逆向技能与作战协议一起屏蔽。 */
    val enabled: Boolean,
    /** 当前索引里属于逆向集合的技能 ID（关闭时为空集合）。 */
    val reverseSkillIds: Set<String>,
    /** 本轮要注入的作战协议；未开启或未配置时为 EMPTY。 */
    val protocol: AgentCombatProtocolSnapshot,
) {
    companion object {
        val DISABLED = ReverseCapabilityDecision(
            enabled = false,
            reverseSkillIds = emptySet(),
            protocol = AgentCombatProtocolSnapshot.EMPTY,
        )
    }
}

/**
 * 逆向能力总开关与实际能力的接线点。
 *
 * 三处消费方：
 * - 技能发现 / 技能读取：只对 [filterVisible] 之后的技能生效；关闭时逆向技能既不出现在
 *   索引里，也不能被 `skills_read` / `skill_resource_read` 读到。
 * - 运行提示注入：开关开启且存在协议正文时，把协议作为独立 system 消息下发（纯文本，不执行）。
 * - 预读偏置：开关开启时给逆向技能族一点命中加权，关闭时不参与。
 */
object ReverseCapabilityRuntime {

    fun isEnabled(context: Context?): Boolean = ReverseCapabilitySettings.isEnabled(context)

    /** 写入总开关；返回是否真的落盘成功（失败时保持原值，不假装切换成功）。 */
    fun setEnabled(context: Context?, enabled: Boolean): Boolean {
        val written = ReverseCapabilitySettings.setEnabled(context, enabled)
        if (written && enabled) AgentCombatProtocolRepository.refresh()
        return written
    }

    /**
     * 解析本轮决定。
     *
     * @param entries 当前索引的全量条目（含未启用项），用于算逆向集合。
     * @param readEnabled 单次读取开关，失败按缺省（开启）处理，避免一次 IO 抖动把能力全关。
     */
    fun resolve(
        context: Context?,
        entries: Collection<SkillIndexEntry>,
        readEnabled: () -> Boolean = { isEnabled(context) },
    ): ReverseCapabilityDecision {
        val enabled = readEnabled()
        if (!enabled) return ReverseCapabilityDecision.DISABLED
        return ReverseCapabilityDecision(
            enabled = true,
            reverseSkillIds = ReverseSkillCatalog.reverseIdsOf(entries),
            protocol = AgentCombatProtocolRepository.loadFor(true),
        )
    }

    /**
     * 技能可见性：关闭总开关时逆向技能从索引与读取通道一起消失。
     *
     * 原版技能与用户新装的非逆向技能不受影响——这是「不要按全部非原版都逆向误屏蔽」的落点。
     */
    fun isSkillVisible(entry: SkillIndexEntry, enabled: Boolean): Boolean =
        enabled || !ReverseSkillCatalog.isReverse(entry)

    fun filterVisible(
        entries: Collection<SkillIndexEntry>,
        enabled: Boolean,
    ): List<SkillIndexEntry> = entries.filter { isSkillVisible(it, enabled) }

    /** 预热协议快照（App 启动或编辑页保存后调用），失败静默。 */
    fun refreshProtocol() {
        runCatching { AgentCombatProtocolRepository.refresh() }
    }
}
