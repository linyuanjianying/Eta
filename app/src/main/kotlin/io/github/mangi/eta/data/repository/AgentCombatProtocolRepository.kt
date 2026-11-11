package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 一次装载的作战协议快照。
 *
 * [content] 已按 [AgentCombatProtocolRepository.MAX_CHARS] 截断；[revision] 是原文的 SHA-256，
 * 用于在系统提示里标记协议版本，也用于压缩/续跑时判断协议是否变过。
 */
data class AgentCombatProtocolSnapshot(
    val content: String,
    val revision: String,
    val byteSize: Int,
    val charCount: Int,
    val fileName: String?,
    val truncated: Boolean,
) {
    /** 存在可注入的正文。文件缺失或只有空白时都是 false。 */
    val available: Boolean get() = !fileName.isNullOrBlank() && content.isNotBlank()

    companion object {
        val EMPTY = AgentCombatProtocolSnapshot(
            content = "",
            revision = "",
            byteSize = 0,
            charCount = 0,
            fileName = null,
            truncated = false,
        )
    }
}

/**
 * 作战协议文件的**只读**装载。
 *
 * 这里存的是用户自己写的提示文本，属于数据：本类只读盘、截断与哈希，
 * 绝不解析、求值、也不把它当成技能/脚本指令去执行。技能正文的加载走
 * `SkillLoader`，两者互不引用，避免把协议当作技能来源。
 *
 * 应用内不再提供编辑/清空入口（界面已移除），文件由用户自行放置或删除；
 * 因此这里只保留读取路径，不再有写入、暂存与备份逻辑。
 *
 * 装载规则：目录 `filesDir/prompts/` 下按文件名排序取**第一个非空 `.md`**；
 * 目录不存在或只有空文件时视为未配置 —— 不产生任何注入。
 */
class AgentCombatProtocolStore(private val rootDir: File) {
    private val promptDir = File(rootDir, DIRECTORY_NAME)
    private val lock = Any()

    fun snapshot(): AgentCombatProtocolSnapshot = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked(): AgentCombatProtocolSnapshot {
        val file = resolveFile() ?: return AgentCombatProtocolSnapshot.EMPTY
        val bytes = try {
            file.readBytes()
        } catch (error: IOException) {
            throw IOException("无法读取作战协议文件 ${file.name}", error)
        }
        return snapshotOf(bytes, file.name)
    }

    /** 截断与哈希的唯一实现，避免两侧口径漂移。 */
    private fun snapshotOf(bytes: ByteArray, fileName: String?): AgentCombatProtocolSnapshot {
        val raw = bytes.toString(Charsets.UTF_8)
        val truncated = raw.length > MAX_CHARS
        val content = if (truncated) {
            raw.take(MAX_CHARS) +
                "\n[作战协议超过 $MAX_CHARS 字符上限，已截断 ${raw.length - MAX_CHARS} 字符；请精简内容]"
        } else {
            raw
        }
        return AgentCombatProtocolSnapshot(
            content = content,
            revision = sha256(bytes),
            byteSize = bytes.size,
            charCount = raw.length,
            fileName = fileName,
            truncated = truncated,
        )
    }

    /** 目录内按文件名排序，返回第一个非空 `.md`；跳过 README、空文件与超限文件。 */
    private fun resolveFile(): File? {
        if (!promptDir.isDirectory) return null
        val candidates = promptDir.listFiles { file ->
            file.isFile &&
                file.name.endsWith(EXTENSION, ignoreCase = true) &&
                !file.name.equals(IGNORED_FILE, ignoreCase = true)
        }?.sortedBy { it.name } ?: return null
        for (candidate in candidates) {
            val length = candidate.length()
            if (length <= 0L || length > MAX_FILE_BYTES) continue
            val text = runCatching { candidate.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            if (text.isNotBlank()) return candidate
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    companion object {
        const val DIRECTORY_NAME = "prompts"
        const val IGNORED_FILE = "README.md"

        /** 注入上限：整篇注入，超限显式截断并留标记——协议每段都是用户指令，不拆段。 */
        const val MAX_CHARS = 96_000

        /** 单文件字节上限。96k 全 CJK 字符按 UTF-8 三字节计约 288 KiB。 */
        const val MAX_FILE_BYTES = 512 * 1024

        private const val EXTENSION = ".md"
    }
}

/**
 * 作战协议的进程级入口。
 *
 * 与 [AgentMemoryRepository] 的分工：记忆是「用户可编辑的背景资料，不是指令」，
 * 作战协议是用户自己的提示文本，因此走独立的 system 消息，绝不塞进 memory_core。
 *
 * 协议是否生效只由 [ReverseCapabilitySettings]（逆向能力总开关）决定，本仓库不再持有第二把开关，
 * 避免「协议开着、技能关着」这类自相矛盾的状态。
 */
object AgentCombatProtocolRepository {

    @Volatile
    private var store: AgentCombatProtocolStore? = null

    fun init(context: Context) {
        if (store == null) {
            synchronized(this) {
                if (store == null) {
                    store = AgentCombatProtocolStore(context.applicationContext.filesDir)
                }
            }
        }
    }

    fun snapshot(): AgentCombatProtocolSnapshot =
        runCatching { store?.snapshot() }.getOrNull() ?: AgentCombatProtocolSnapshot.EMPTY

    /** 刷新一次磁盘快照（启动、切换总开关、进入运行前调用），失败静默保留缺省空快照。 */
    fun refresh(): AgentCombatProtocolSnapshot = snapshot()

    /**
     * 运行期装载：未开启或文件为空时返回 [AgentCombatProtocolSnapshot.EMPTY]，调用方据此不注入。
     *
     * 只返回快照，不执行其中任何内容。
     */
    fun loadFor(enabled: Boolean): AgentCombatProtocolSnapshot =
        if (enabled) {
            runCatching { snapshot() }.getOrDefault(AgentCombatProtocolSnapshot.EMPTY)
        } else {
            AgentCombatProtocolSnapshot.EMPTY
        }
}
