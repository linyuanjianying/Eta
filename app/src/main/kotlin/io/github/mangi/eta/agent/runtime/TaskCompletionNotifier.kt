package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

/**
 * 任务结束通知：所有对话共用。
 *
 * 以前只有灵屏路由的 run 会在结束时发一条「任务完成」，普通对话跑完什么都不留，
 * 用户切走后不知道结果。这里把它抽成通用出口：
 *
 * - 一条对话一个稳定 ID（[SessionNotificationIds.completionIdFor]），并发不会互相覆盖；
 * - 点通知直接落到对应会话（[io.github.mangi.eta.ui.MainActivity.EXTRA_CONVERSATION_ID]）；
 * - SDK 36 起补 `setShortCriticalText`，和运行中的实时活动卡一致，进流体云。
 *
 * 通知不常驻（autoCancel），用户划掉或点开即消失；运行中的进度卡仍由
 * 系统执行服务负责，两者 ID 分段，互不打扰。
 */
internal object TaskCompletionNotifier {

    private const val CHANNEL = "eta_task_completion"
    private const val LIVE_SDK = 36

    fun show(
        context: Context,
        ok: Boolean,
        detail: String,
        stopped: Boolean = false,
        conversationId: String = "",
        conversationTitle: String = "",
        runId: String = "",
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AndroidAgentLogger.warn("Completion notification skipped: POST_NOTIFICATIONS not granted")
            return
        }
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.task_completion_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "对话跑完时提醒结果"
                    enableVibration(true)
                    setShowBadge(true)
                },
            )
            val title = when {
                stopped -> context.getString(R.string.task_completion_stopped_title)
                ok -> context.getString(R.string.task_completion_ok_title)
                else -> context.getString(R.string.task_completion_fail_title)
            }
            val summary = detail.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?.take(120)
                ?.ifBlank { context.getString(R.string.task_completion_ok_body) }
                ?: context.getString(R.string.task_completion_ok_body)
            val body = if (conversationTitle.isBlank()) summary else "$conversationTitle · $summary"
            val builder = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_STATUS)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(openIntent(context, conversationId))
            if (Build.VERSION.SDK_INT >= LIVE_SDK) {
                builder.setShortCriticalText(
                    when {
                        stopped -> context.getString(R.string.task_completion_capsule_stopped)
                        ok -> context.getString(R.string.task_completion_capsule_ok)
                        else -> context.getString(R.string.task_completion_capsule_fail)
                    },
                )
            }
            manager.notify(
                SessionNotificationIds.completionIdFor(conversationId.ifBlank { runId }),
                builder.build(),
            )
        } catch (t: Throwable) {
            AndroidAgentLogger.warn("Completion notification failed: type=${t.safeLogType()}")
        }
    }

    /** 点通知直达对应会话；没有会话（系统入口、子智能体）时只把 App 拉到前台。 */
    private fun openIntent(context: Context, conversationId: String): PendingIntent {
        val intent = Intent(context, io.github.mangi.eta.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (conversationId.isNotBlank()) {
            intent.putExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_CONVERSATION_ID, conversationId)
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
