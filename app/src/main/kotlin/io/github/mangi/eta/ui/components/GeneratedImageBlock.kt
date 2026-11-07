package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 缩略图上限：100dp 在 3x 屏上是 300px，40 万像素够用，再大纯属浪费内存。
 */
internal const val THUMBNAIL_MAX_PIXELS = 400_000L

/**
 * 按像素上限做 2 的幂降采样。
 *
 * 历史消息里存的是 1600 长边的展示档，直接按原尺寸解成 100dp 缩略图，
 * 每张要占 7.7MB ARGB，一屏十几张就够 OOM 了。
 */
@Composable
internal fun rememberDataUrlBitmap(dataUrl: String, maxPixels: Long = 0L) =
    remember(dataUrl, maxPixels) { decodeDataUrlBitmap(dataUrl) }

/**
 * 全屏看图。
 *
 * 缩略图只有 60/100dp，字看不清、细节全糊，所以点开必须给「原图铺满 + 可放大」：
 * 单击任意处或返回键关闭。
 *
 * 为什么用 `usePlatformDefaultWidth = false`：默认 Dialog 宽度受系统主题限制（约 320dp），
 * 那样只是把缩略图放大到一张卡片大小，等于没放大。
 */
@Composable
internal fun ImageViewerDialog(
    dataUrl: String?,
    onDismiss: () -> Unit,
) {
    if (dataUrl == null) return
    val bitmap = rememberDataUrlBitmap(dataUrl) ?: return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.chat_image_attachment),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 生图结果展示。
 *
 * 与用户附件气泡（右对齐、100dp 缩略图）区分开：这里是助手侧产物，
 * 左对齐、按可用宽度铺满一小块，点开看大图。放在回复下方而不是气泡里，
 * 是因为它不属于任何一方的「发言」。
 */
@Composable
internal fun GeneratedImageBlock(
    images: List<String>,
    caption: String = "",
    modifier: Modifier = Modifier,
) {
    var viewedImage by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEach { dataUrl ->
            // 展示档比缩略图大：生成图是内容本身，看太糊就没有意义。
            val bitmap = rememberDataUrlBitmap(dataUrl, GENERATED_IMAGE_MAX_PIXELS)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.chat_image_attachment),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(interactionSource = null, indication = null) {
                            viewedImage = dataUrl
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        }
        if (caption.isNotBlank()) {
            top.yukonga.miuix.kmp.basic.Text(
                text = caption,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    ImageViewerDialog(dataUrl = viewedImage, onDismiss = { viewedImage = null })
}

/** 生成图的展示像素上限：比缩略图大一个量级，又不至于把内存吃满。 */
private const val GENERATED_IMAGE_MAX_PIXELS = 2_000_000L
