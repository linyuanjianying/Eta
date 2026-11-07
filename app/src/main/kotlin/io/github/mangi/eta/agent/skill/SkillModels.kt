package io.github.mangi.eta.agent.skill

import androidx.compose.runtime.Immutable

/**
 * 技能索引条目——对应磁盘上一个 [SKILL.md] 文件的元信息。
 */
@Immutable
data class SkillIndexEntry(
    val id: String,
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val rootPath: String,
    val skillFilePath: String,
    val hasScripts: Boolean,
    val hasReferences: Boolean,
    val hasAssets: Boolean,
    val hasEvals: Boolean,
    val enabled: Boolean = true,
    val source: String = "user",
    val installed: Boolean = true,
)

/**
 * 已解析的技能上下文——包含 SKILL.md 正文和附属目录路径。
 */
@Immutable
data class ResolvedSkillContext(
    val skillId: String,
    val frontmatter: Map<String, String>,
    val metadata: Map<String, String> = emptyMap(),
    val bodyMarkdown: String,
    val loadedReferences: List<String> = emptyList(),
    val scriptsDir: String? = null,
    val assetsDir: String? = null,
    val triggerReason: String,
)

@Immutable
data class SkillCompatibilityResult(
    val available: Boolean,
    val reason: String? = null,
)

/**
 * 单次 Agent 运行中解析出的技能上下文集合。
 */
@Immutable
data class SkillContext(
    val installedSkills: List<SkillIndexEntry> = emptyList(),
    /** 逆向能力总开关是否开启；索引已按此过滤，这里再留一份供提示词分组与偏置使用。 */
    val reverseModeEnabled: Boolean = false,
    /** 当前索引里属于逆向集合的技能 ID；关闭总开关时为空集合。 */
    val reverseSkillIds: Set<String> = emptySet(),
) {
    companion object {
        val EMPTY = SkillContext()
    }
}

/** SKILL.md 文件解析结果。 */
internal data class ParsedSkillFile(
    val frontmatter: Map<String, String>,
    val body: String,
)
