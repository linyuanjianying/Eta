package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 「逆向能力」统一开关的持久化。
 *
 * 刻意用 `filesDir` 下的**原子小文件**而不是 SharedPreferences / DataStore：
 * - 开关同时被 App 主进程的设置页与 Runtime 服务进程（可能还有 hook 进程）读取，
 *   SharedPreferences 每进程各自缓存一份，跨进程写入后另一方可能长期读到旧值；
 * - 文件每次读取都直接落盘，写入用「临时文件 + 原子 rename」，跨进程实时可见，
 *   也不需要关心别人的 DataStore key 迁移。
 *
 * 缺省值 **true**：安装即保留当前已经可用的技能体验，用户显式关闭才屏蔽。
 * 任何 IO 失败都按缺省（开启）处理，避免一次瞬时错误把能力全关掉。
 */
object ReverseCapabilitySettings {

    private const val FILE_NAME = "reverse_capability.enabled"
    private const val STAGED_SUFFIX = ".writing"
    private const val ENABLED_VALUE = "true"
    private const val DISABLED_VALUE = "false"

    @Volatile
    private var rootDir: File? = null

    fun init(context: Context) {
        if (rootDir == null) {
            synchronized(this) {
                if (rootDir == null) rootDir = context.applicationContext.filesDir
            }
        }
    }

    /** 直接绑定一个目录（单测 / 非 Android 宿主）。 */
    fun init(rootDir: File) {
        synchronized(this) { this.rootDir = rootDir }
    }

    private fun resolveFile(): File? = rootDir?.let { File(it, FILE_NAME) }

    fun isEnabled(context: Context? = null): Boolean {
        context?.let(::init)
        val file = resolveFile() ?: return true
        return runCatching {
            if (!file.isFile) return@runCatching true
            file.readText(Charsets.UTF_8).trim().lowercase() != DISABLED_VALUE
        }.getOrDefault(true)
    }

    /**
     * 写入开关。先写 `.writing` 临时文件再原子替换，使其他进程永远不会看到半截内容。
     *
     * @return 实际落盘结果是否成功；失败时内存状态与磁盘保持一致（仍读旧值）。
     */
    fun setEnabled(context: Context?, enabled: Boolean): Boolean {
        context?.let(::init)
        val file = resolveFile() ?: return false
        val parent = file.parentFile ?: return false
        return runCatching {
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                return@runCatching false
            }
            val staged = File(parent, file.name + STAGED_SUFFIX)
            staged.writeText(if (enabled) ENABLED_VALUE else DISABLED_VALUE, Charsets.UTF_8)
            try {
                Files.move(
                    staged.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        }.getOrElse {
            runCatching {
                resolveFile()?.let { f -> File(f.parentFile, f.name + STAGED_SUFFIX).delete() }
            }
            false
        }
    }
}
