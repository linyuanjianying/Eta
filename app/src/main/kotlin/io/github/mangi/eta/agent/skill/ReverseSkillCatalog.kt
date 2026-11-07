package io.github.mangi.eta.agent.skill

/**
 * 逆向能力集合的判定口径。
 *
 * 判定刻意从「技能自身是否声明属于逆向族」出发，而不是「所有非原版技能都算逆向」：
 * - 原版自带技能（[NATIVE_SKILL_IDS]）永远不属于逆向集合；
 * - 用户新装的非逆向技能（文档工具、写作助手之类）也不属于逆向集合；
 * - 只有技能自带族标记（frontmatter `metadata.family` / `metadata.category`），
 *   或 ID 命中随包内置的逆向技能清单时才算。
 *
 * 为什么不用「全部非原版 = 逆向」：那样每装一个新技能都会被算进逆向集合，
 * 总开关一关就把与逆向无关的新技能一起屏蔽，看起来就是「关开关后技能全没了」。
 */
object ReverseSkillCatalog {
    /** eta 原版自带的技能；任何情况下都不算逆向能力。 */
    val NATIVE_SKILL_IDS: Set<String> = setOf(
        "self-improving-agent",
        "skill-creator",
        "skill-installer",
    )

    /**
     * 随包内置的逆向 / 安全研究技能 ID。
     *
     * 只兜底「registry / frontmatter 里还没有族标记的历史安装」；正常路径走族标记。
     * 新增随包逆向技能时把 ID 补进来即可，不需要改动判定逻辑。
     */
    val BUILTIN_REVERSE_SKILL_IDS: Set<String> = setOf(
        "anti-cheat",
        "anti-cheat-systems",
        "anti-frida-bypass",
        "api-security",
        "apk-re",
        "apk-reverse",
        "apkseep",
        "asm-analysis",
        "attack-chain",
        "binary-analysis",
        "binary-diff",
        "binary-ninja-reverse",
        "browser-extension-reverse",
        "capture-mitm",
        "client-license-validation-bypass",
        "cloud-k8s",
        "code-audit",
        "coldbrew-api-reverse",
        "coldbrew-identity",
        "coldbrew-native-reverse",
        "coldbrew-protocol-reverse",
        "crack-keygen",
        "ctf-sandbox",
        "database-security",
        "digital-forensics",
        "dma-attack-techniques",
        "dotnet-reverse",
        "dsl-vm-reverse",
        "dynamic-instrumentation",
        "edr-bypass-re",
        "elf-local-auth-patcher",
        "email-security",
        "firmware-pentest",
        "full-crack",
        "full-pentest",
        "game-cheat",
        "game-hacking-techniques",
        "game-security-research-rigor",
        "ghidra-reverse",
        "go-rust-reverse",
        "graphics-api-hooking",
        "hardware-security",
        "hw-trace",
        "ida-reverse",
        "identity-federation",
        "il2cpp-dump",
        "js-reverse",
        "linker-fake-load-unwrapper",
        "llm-security",
        "macos-reverse",
        "malware-analysis",
        "mobile-reverse",
        "mobile-security",
        "network-pentest",
        "ot-ics",
        "patch-diff-exploit",
        "pentest-tools",
        "protocol-reverse",
        "protocol-reverse-engineering",
        "protocol-reversing",
        "pwn-chain",
        "radare2",
        "radio-sdr",
        "reverse-delivery",
        "reverse-engineering",
        "reverse-engineering-api",
        "reverse-task-planner",
        "seagull-anticheat",
        "seagull-auto-ops",
        "seagull-evasion",
        "seagull-exploit",
        "seagull-game-hack",
        "seagull-game-redteam",
        "seagull-game-security",
        "seagull-lab",
        "seagull-license-security",
        "seagull-malware",
        "seagull-memory",
        "seagull-pentest",
        "seagull-reverse",
        "seagull-social-eng",
        "seagull-unlimited",
        "softseep",
        "src-hunt",
        "supply-chain-security",
        "thick-client",
        "threat-hunting",
        "threat-intelligence",
        "wifi-wireless",
        "windows-ad",
        "windows-kernel-security",
        "xigong-funk-hikari",
        "yingan-tuoxiu",
        // 随包 eni-* 逆向 / 安全研究技能族；文档、绘图、开发与通用流程类 eni-* 刻意不在其中。
        "eni-api-assurance-lane",
        "eni-api-security",
        "eni-apk-reverse",
        "eni-attack-chain",
        "eni-binary-diff",
        "eni-blackbox-reverse-boost",
        "eni-burp-mcp",
        "eni-cf-security-lab-router",
        "eni-cloud-container-workflow",
        "eni-code-security-workflow",
        "eni-crack-workflow",
        "eni-ctf-orchestrator",
        "eni-edr-bypass",
        "eni-firmware-pentest",
        "eni-firmware-workflow",
        "eni-game-cheat-lab-workflow",
        "eni-game-hacking",
        "eni-game-security",
        "eni-ida-reverse",
        "eni-js-reverse",
        "eni-license-security",
        "eni-llm-security",
        "eni-malware-analysis",
        "eni-malware-ir-workflow",
        "eni-memory-forensics",
        "eni-mobile-reverse",
        "eni-mobile-security-workflow",
        "eni-patch-diff-exploit",
        "eni-pentest-advanced",
        "eni-pentest-tools",
        "eni-pentest-workflow",
        "eni-pwn-chain",
        "eni-radare2",
        "eni-reverse-deep",
        "eni-reverse-ref",
        "eni-reverse-workflow",
        "eni-reverselab-bridge",
        "eni-reverselab-platform",
        "eni-supply-chain",
        "eni-supply-chain-assurance-lane",
        "eni-unpack-reverse-lab",
        "eni-fuzzing-workflow",
        "eni-redteam-ad-detail-pack",
        "eni-redteam-api-detail-pack",
        "eni-redteam-auth-detail-pack",
        "eni-redteam-cache-poison-detail-pack",
        "eni-redteam-clickjacking-detail-pack",
        "eni-redteam-cloud-detail-pack",
        "eni-redteam-cmdi-detail-pack",
        "eni-redteam-code-audit-detail-pack",
        "eni-redteam-container-detail-pack",
        "eni-redteam-cors-miscfg-detail-pack",
        "eni-redteam-crypto-detail-pack",
        "eni-redteam-csrf-detail-pack",
        "eni-redteam-cve-lookup",
        "eni-redteam-deserialize-detail-pack",
        "eni-redteam-doctrine",
        "eni-redteam-evasion-detail-pack",
        "eni-redteam-file-detail-pack",
        "eni-redteam-injection-detail-pack",
        "eni-redteam-logic-detail-pack",
        "eni-redteam-mobile-detail-pack",
        "eni-redteam-network-detail-pack",
        "eni-redteam-open-redirect-detail-pack",
        "eni-redteam-payload-detail-pack",
        "eni-redteam-postex-detail-pack",
        "eni-redteam-recon-detail-pack",
        "eni-redteam-recon-intake",
        "eni-redteam-reverse-detail-pack",
        "eni-redteam-sqli-detail-pack",
        "eni-redteam-ssrf-detail-pack",
        "eni-redteam-ssti-detail-pack",
        "eni-redteam-subdomain-takeover-detail-pack",
        "eni-redteam-web-detail-pack",
        "eni-redteam-xss-detail-pack",
        "eni-redteam-xxe-detail-pack",
    )

    /** frontmatter `metadata.family` / `metadata.category` 里表示逆向族的取值。 */
    private val REVERSE_FAMILY_MARKERS: Set<String> = setOf(
        "reverse",
        "reverse-engineering",
        "reversing",
        "security",
        "pentest",
        "crack",
        "malware",
        "redteam",
        "red-team",
    )

    /**
     * 明确不属于逆向能力的技能（文档、绘图、开发与通用编排）。
     *
     * 即使它们带了 `category: security` 之类的宽泛标记，也按此表优先判定为非逆向，
     * 保证总开关关闭时不会把它们一起屏蔽。
     */
    val NON_REVERSE_SKILL_IDS: Set<String> = setOf(
        "eni-architecture-workflow",
        "eni-browser-automation",
        "eni-browser-research-workflow",
        "eni-case-lab",
        "eni-core",
        "eni-diagram",
        "eni-docs",
        "eni-field-journal",
        "eni-five-edge",
        "eni-github-workflow-hub",
        "eni-kali",
        "eni-scraper-workflow",
        "eni-software-workflow",
        "eni-unified-router",
        "eni-universal-workflow",
    )

    private val FAMILY_KEYS: Set<String> = setOf("family", "category")

    /** 判定单个技能是否属于逆向集合。 */
    fun isReverse(entry: SkillIndexEntry): Boolean = isReverse(
        skillId = entry.id,
        metadata = entry.metadata,
    )

    /**
     * 判定口径的唯一实现。
     *
     * @param skillId 技能 ID。
     * @param metadata 技能 frontmatter 的 `metadata` 映射；用于技能自带族标记。
     *
     * 来源（随包 / 用户安装）刻意不参与判定：判别依据是技能自我声明，不是它从哪来。
     */
    fun isReverse(
        skillId: String,
        metadata: Map<String, String> = emptyMap(),
    ): Boolean {
        val id = skillId.trim().lowercase()
        if (id.isBlank() || id in NATIVE_SKILL_IDS) return false
        if (id in NON_REVERSE_SKILL_IDS) return false
        if (declaresReverseFamily(metadata)) return true
        if (id in BUILTIN_REVERSE_SKILL_IDS) return true
        // 未声明逆向族的技能一律不算：来源、是否随包、是否新装都不构成逆向身份。
        // 因此新装的文档 / 绘图 / 开发 / 通用技能不会被总开关误屏蔽。
        return false
    }

    /** 由索引条目算出逆向集合。 */
    fun reverseIdsOf(entries: Collection<SkillIndexEntry>): Set<String> =
        entries.filter { isReverse(it) }.mapTo(mutableSetOf()) { it.id }

    /** 只剩原版技能的极端情况：此时总开关没有可启停的目标。 */
    fun hasReverseTargets(entries: Collection<SkillIndexEntry>): Boolean =
        entries.any { isReverse(it) }

    private fun declaresReverseFamily(metadata: Map<String, String>): Boolean =
        metadata.entries.any { (key, value) ->
            if (key.trim().lowercase() !in FAMILY_KEYS) return@any false
            val parts = value.trim().lowercase().split(',', ' ', '/', ';')
            parts.any { it.trim() in REVERSE_FAMILY_MARKERS }
        }
}
