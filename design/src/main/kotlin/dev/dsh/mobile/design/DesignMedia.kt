// 图片附件：采样解码、附件预览与保存/分享。
package dev.dsh.mobile.design

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/**
 * 图片消息气泡：本地文件直接解码。
 * 点开全屏预览、长按出菜单、下面一行「文件名 · 宽×高」点一下复制路径；
 * 文件被删/没权限时显示「图片已失效 + 路径」，不留白块。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentImage(path: String, mine: Boolean) {
    val p = LocalQq.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val file = remember(path) { File(path) }
    val bmpState = remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val failed = remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        // 缩略图真实显示高度 220.dp（heightIn(max=220.dp)），2x 过采样兼顾预览清晰度。
        // 不能用整屏尺寸作目标：高分辨率屏上 4032x3024 图会算出 sample=1 仍整图解码（~49MB ARGB，OOM 风险）。
        val density = context.resources.displayMetrics.density
        val targetPx = (220f * density).roundToInt() * 2
        val decoded = try {
            withContext(Dispatchers.IO) {
                decodeImageSampled(path, targetPx, targetPx)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // OOM / 损坏图片：标记失效，不让异常抛穿 LaunchedEffect 打崩协程。
            null
        }
        if (decoded != null) bmpState.value = decoded else failed.value = true
    }
    val bmp = bmpState.value
    var preview by remember(path) { mutableStateOf(false) }
    var menu by remember(path) { mutableStateOf(false) }
    val copyPath: () -> Unit = { clipboard.setText(AnnotatedString(path)) }
    Column(Modifier.padding(bottom = 6.dp)) {
        Box {
            when {
                bmp != null -> {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = { preview = true },
                                onLongClick = { menu = true },
                            ),
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.design_media_copy_path), fontSize = 13.sp) },
                            onClick = { menu = false; copyPath() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.design_media_open_with), fontSize = 13.sp) },
                            onClick = { menu = false; openWith(context, file) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.design_media_save_gallery), fontSize = 13.sp) },
                            onClick = { menu = false; saveToGallery(context, file) },
                        )
                    }
                }
                failed.value -> {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.bg)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.design_media_image_gone, path),
                            color = p.t3,
                            fontSize = 11.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                else -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.common_loading), color = p.t3, fontSize = 12.sp)
                    }
                }
            }
        }
        val caption = buildList {
            add(file.name)
            if (bmp != null) add("${bmp.width}×${bmp.height}")
        }.joinToString(" · ")
        Text(
            caption,
            color = if (mine) Color.White.copy(alpha = 0.75f) else p.t3,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 3.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { copyPath() }
                .padding(horizontal = 2.dp, vertical = 1.dp),
        )
    }
    if (preview && bmp != null) {
        Dialog(
            onDismissRequest = { preview = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { preview = false },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "×",
                    color = Color.White,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(QqSpace.xl())
                        .clickable { preview = false },
                )
            }
        }
    }
}

/** 目标尺寸降采样采样率：2 的幂，保证解码后不小于 [reqWidth]×[reqHeight]，避免整图载入。 */
internal fun computeInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while ((halfHeight / sample) >= reqHeight && (halfWidth / sample) >= reqWidth) {
            sample *= 2
        }
    }
    return sample
}

/** 本地图片按目标尺寸降采样解码；文件不存在/损坏返回 null。须在 IO 线程调用。 */
private fun decodeImageSampled(path: String, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    val opts = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = computeInSampleSize(w, h, reqWidth, reqHeight)
    }
    return android.graphics.BitmapFactory.decodeFile(path, opts)
}

/** 用系统看图或其他应用打开本地文件（走 FileProvider，app 已注册 ${packageName}.fileprovider）。 */
private fun openWith(context: android.content.Context, file: File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            android.content.Intent.createChooser(view, context.getString(R.string.design_media_open_with))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 复制进系统相册（Pictures/DSH）；API 29+ 走 MediaStore 的 RELATIVE_PATH。 */
private fun saveToGallery(context: android.content.Context, file: File) {
    runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DSH")
            }
        }
        val target = context.contentResolver
            .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
        context.contentResolver.openOutputStream(target)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
    }
}
