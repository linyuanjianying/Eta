package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * 用户自备的「编辑作战协议」文本的装载、原子写入与清理。
 *
 * 这里存的是**用户自己写的提示文本**，属于数据：本类只做读写与截断，
 * 绝不解析、求值、也不把它当成技能/脚本指令去执行。技能正文的加载走
 * `SkillLoader`，两者互不引用，避免把协议当作技能来源。
 *
 * 装载规则：目录 `filesDir/prompts/` 下按文件名排序取**第一个非空 `.md`**；
 * 目录不存在或只有空文件时视为未配置 —— 不产生任何注入，也不写半成品。
 */
class AgentCombatProtocolStore(private val rootDir: File) {
    private val promptDir = File(rootDir, DIRECTORY_NAME)
    private val backupDir = File(promptDir, BACKUP_DIRECTORY_NAME)
    private val lock = Any()

    fun snapshot(): AgentCombatProtocolSnapshot = synchronized(lock) { snapshotLocked() }

    /** 写入当前生效的那份文件；还没有文件时写默认名。 */
    fun write(content: String): AgentCombatProtocolSnapshot = synchronized(lock) {
        writeLocked(content, resolveFile()?.name ?: DEFAULT_FILE_NAME)
        snapshotLocked()
    }

    /** 清空当前生效的那份文件；清空前先在同一目录的备份子目录留一份副本。 */
    fun clear(): AgentCombatProtocolSnapshot = synchronized(lock) { clearLocked() }

    private fun clearLocked(): AgentCombatProtocolSnapshot {
        resolveFile()?.let { active ->
            runCatching {
                if (!backupDir.exists() && !backupDir.mkdirs() && !backupDir.isDirectory) {
                    error("无法创建备份目录")
                }
                val stamp = System.currentTimeMillis()
                active.copyTo(File(backupDir, "${active.name}.$stamp$BACKUP_SUFFIX"), overwrite = true)
            }
            if (!active.delete() && active.exists()) {
                throw IOException("无法删除作战协议文件")
            }
        }
        return snapshotLocked()
    }

    private fun snapshotLocked(): AgentCombatProtocolSnapshot {
        val file = resolveFile() ?: return AgentCombatProtocolSnapshot.EMPTY
        val bytes = try {
            file.readBytes()
        } catch (error: IOException) {
            throw IOException("无法读取作战协议文件 ${file.name}", error)
        }
        return snapshotOf(bytes, file.name)
    }

    /** 截断与哈希的唯一实现，避免读写两侧口径漂移。 */
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

    private fun writeLocked(content: String, fileName: String) {
        val name = sanitizeFileName(fileName)
        if (!promptDir.exists() && !promptDir.mkdirs() && !promptDir.isDirectory) {
            throw IOException("无法创建 $DIRECTORY_NAME 目录")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw IOException("作战协议不能超过 ${MAX_FILE_BYTES / 1024} KiB UTF-8 字节")
        }
        val target = File(promptDir, name)
        val staged = File(promptDir, "$name$STAGED_SUFFIX")
        try {
            staged.writeBytes(bytes)
            if (!staged.renameTo(target)) {
                staged.copyTo(target, overwrite = true)
                staged.delete()
            }
        } catch (error: IOException) {
            runCatching { staged.delete() }
            throw IOException("无法保存作战协议", error)
        }
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

    /** 只取文件名，禁止路径穿越；非法输入回落到默认名。 */
    private fun sanitizeFileName(fileName: String): String {
        val trimmed = fileName.trim()
        if (trimmed.isEmpty()) return DEFAULT_FILE_NAME
        val base = File(trimmed).name
        if (base.isEmpty() || base.startsWith(".") || !base.endsWith(EXTENSION, ignoreCase = true)) {
            return DEFAULT_FILE_NAME
        }
        return base
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    companion object {
        const val DIRECTORY_NAME = "prompts"
        const val DEFAULT_FILE_NAME = "作战协议.md"
        const val IGNORED_FILE = "README.md"

        /** 注入上限：整篇注入，超限显式截断并留标记——协议每段都是用户指令，不拆段。 */
        const val MAX_CHARS = 96_000

        /** 单文件字节上限。96k 全 CJK 字符按 UTF-8 三字节计约 288 KiB。 */
        const val MAX_FILE_BYTES = 512 * 1024

        private const val EXTENSION = ".md"
        private const val BACKUP_SUFFIX = ".bak"
        private const val STAGED_SUFFIX = ".writing"
        private const val BACKUP_DIRECTORY_NAME = ".backup"
    }
}

/**
 * 作战协议的进程级入口。
 *
 * 与 [AgentMemoryRepository] 的分工：记忆是「用户可编辑的背景资料，不是指令」，
 * 作战协议是**用户自己的提示文本**，因此走独立的 system 消息，绝不塞进 memory_core。
 *
 * 协议是否生效只由 [ReverseCapabilitySettings]（逆向能力总开关）决定，本仓库不再持有第二把开关，
 * 避免「协议开着、技能关着」这类自相矛盾的状态。
 */
object AgentCombatProtocolRepository {

    @Volatile
    private var store: AgentCombatProtocolStore? = null

    private val state = MutableStateFlow(AgentCombatProtocolSnapshot.EMPTY)

    fun init(context: Context) {
        if (store == null) {
            synchronized(this) {
                if (store == null) {
                    val created = AgentCombatProtocolStore(context.applicationContext.filesDir)
                    state.value = runCatching { created.snapshot() }
                        .getOrDefault(AgentCombatProtocolSnapshot.EMPTY)
                    store = created
                }
            }
        }
    }

    private fun ensureStore(): AgentCombatProtocolStore? = store

    fun snapshot(): AgentCombatProtocolSnapshot =
        runCatching { ensureStore()?.snapshot() }.getOrNull() ?: AgentCombatProtocolSnapshot.EMPTY

    fun write(content: String): AgentCombatProtocolSnapshot {
        val target = ensureStore() ?: throw IOException("作战协议仓库尚未初始化")
        return target.write(content).also { state.value = it }
    }

    fun clear(): AgentCombatProtocolSnapshot {
        val target = ensureStore() ?: throw IOException("作战协议仓库尚未初始化")
        return target.clear().also { state.value = it }
    }

    /** 编辑页订阅用：保存/清空后立即反映新快照。 */
    fun snapshotFlow(): Flow<AgentCombatProtocolSnapshot> = state.asStateFlow()

    /** 刷新一次磁盘快照，供编辑页进入时取最新内容。 */
    fun refresh(): AgentCombatProtocolSnapshot =
        snapshot().also { refreshed -> state.value = refreshed }

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
