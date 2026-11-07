package io.github.mangi.eta.ui.components

import android.content.ContentValues
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val THUMBNAIL_MAX_PIXELS = 400_000L

@Composable
internal fun rememberDataUrlBitmap(dataUrl: String, maxPixels: Long) =
    remember(dataUrl, maxPixels) { decodeDataUrlBitmap(dataUrl, maxPixels) }

/** 保存引用里的原始编码字节，不对预览 Bitmap 再压缩。 */
@Composable
internal fun rememberSaveChatImage(): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    return { reference ->
        if (!saving) {
            saving = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val encoded = reference.substringAfter("base64,", "")
                        require(encoded.isNotBlank()) { "图片原始数据不可用" }
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        require(bytes.isNotEmpty()) { "图片数据为空" }
                        val mime = reference.substringAfter("data:").substringBefore(';')
                        val extension = when (mime) {
                            "image/png" -> "png"
                            "image/webp" -> "webp"
                            "image/gif" -> "gif"
                            "image/heic", "image/heif" -> "heic"
                            "image/avif" -> "avif"
                            else -> "jpg"
                        }
                        val resolver = context.contentResolver
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "Eta-${java.util.UUID.randomUUID()}.$extension")
                            put(MediaStore.Images.Media.MIME_TYPE, mime)
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Eta")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                        try {
                            checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
                            check(resolver.update(uri, ContentValues().apply {
                                put(MediaStore.Images.Media.IS_PENDING, 0)
                            }, null, null) > 0)
                        } catch (failure: Exception) {
                            resolver.delete(uri, null, null)
                            throw failure
                        }
                    }
                }
                saving = false
                Toast.makeText(context, if (result.isSuccess) "原图已保存到相册 Pictures/Eta" else "保存失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ImageViewerDialog(dataUrl: String?, onDismiss: () -> Unit) {
    if (dataUrl == null) return
    val bitmap = rememberDataUrlBitmap(dataUrl, 8_000_000L) ?: return
    val saveImage = rememberSaveChatImage()
    var scale by remember(dataUrl) { mutableFloatStateOf(1f) }
    var offsetX by remember(dataUrl) { mutableFloatStateOf(0f) }
    var offsetY by remember(dataUrl) { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)
            .combinedClickable(onClick = onDismiss, onLongClick = { saveImage(dataUrl) }), contentAlignment = Alignment.Center) {
            Image(bitmap, stringResource(R.string.chat_image_attachment), contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY
                }.pointerInput(dataUrl) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        if (scale > 1f) { offsetX += pan.x; offsetY += pan.y }
                        else { offsetX = 0f; offsetY = 0f }
                    }
                })
            top.yukonga.miuix.kmp.basic.Text("双指缩放 · 长按保存原图 · 点击关闭", color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun GeneratedImageBlock(images: List<String>, caption: String = "", modifier: Modifier = Modifier) {
    var viewedImage by remember { mutableStateOf<String?>(null) }
    val saveImage = rememberSaveChatImage()
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEach { reference ->
            val bitmap = rememberDataUrlBitmap(reference, 2_000_000L)
            if (bitmap != null) Image(bitmap, stringResource(R.string.chat_image_attachment),
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = { viewedImage = reference }, onLongClick = { saveImage(reference) }),
                contentScale = ContentScale.Fit)
        }
        if (caption.isNotBlank()) top.yukonga.miuix.kmp.basic.Text(caption,
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    ImageViewerDialog(viewedImage) { viewedImage = null }
}
