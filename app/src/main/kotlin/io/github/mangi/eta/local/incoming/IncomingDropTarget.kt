package io.github.mangi.eta.local.incoming

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import io.github.mangi.eta.ui.IncomingShareRequest

/**
 * 聊天舞台的拖放接收区。
 *
 * 只处理「拖进 App 的任意内容」这一种交互：文件管理器拖文件/图片、浏览器拖链接或选中文本。
 * 解析复用分享用的 [IncomingContentParser]，因此文件类型、大小、私有 file:// 的过滤规则
 * 与系统分享完全一致，不会出现「拖进来能读、分享进来被拒」这种两套标准。
 *
 * 为什么用 dragAndDropTarget 而不是自己算坐标：拖放事件只有在目标声明接收 MIME 后才会真正送达，
 * 声明得越宽（text / uri-list / image / 任意文件），ColorOS 文件管理等第三方拖拽源才越不会
 * 中途「拖了没反应」。
 */
@Composable
internal fun rememberIncomingDropTarget(
    enabled: Boolean,
    onIncoming: (IncomingShareRequest) -> Unit,
): Modifier {
    val target = remember(enabled, onIncoming) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                if (!enabled) return false
                val payload = IncomingContentParser.fromDragEvent(event.toAndroidDragEvent())
                    ?: return false
                if (payload.isEmpty) return false
                onIncoming(
                    IncomingShareRequest(
                        uris = payload.uris.map { it.toString() },
                        mimeType = payload.mimeType,
                        text = payload.text.takeIf { it.isNotBlank() },
                    ),
                )
                return true
            }
        }
    }
    return if (!enabled) Modifier else Modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.mimeTypes().isNotEmpty() },
        target = target,
    )
}
