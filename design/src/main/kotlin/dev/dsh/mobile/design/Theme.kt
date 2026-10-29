package dev.dsh.mobile.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.mobile.core.model.BackMaterial
import java.io.File
import java.io.InputStream

/**
 * User-facing design knobs. Everything a user can change (theme mode, accent, bubble
 * colour, radius, font scale, density, compact mode) lives here; screens read tokens,
 * never literal colours.
 */
data class ThemeTokens(
    val accent: Color = Color(0xFF0099FF),
    val accent2: Color = Color(0xFF3CB4FF),
    val bubbleMine: Color = Color(0xFF0099FF),
    val bubbleOther: Color = Color(0xFFF1F2F5),
    val cornerRadius: Dp = 18.dp,
    val fontScale: Float = 1f,
    val density: Float = 1f,
    val compact: Boolean = false,
    val dark: Boolean = false,

    // ---- 外观（对应 SettingsStore 的 app_icon_* / background_* / glass_* / border_width）----
    /** `system` = 内置矢量图标；`custom` = 用 [customIconUri] 的图片。 */
    val appIconStyle: String = "system",
    /** 自定义图标 uri（content://）；空 = 未设置。 */
    val customIconUri: String = "",
    /** 自定义背景图 uri；空 = 纯色背景。 */
    val backgroundUri: String = "",
    /** 背景压暗 0f-0.8f。 */
    val backgroundDim: Float = 0f,
    /** 主色调色相 0-360；负值 = 不接管，沿用 [accent]。 */
    val accentHue: Float = -1f,
    /** 卡片 / 面板边框宽度。 */
    val borderWidth: Dp = 0.dp,
    /**
     * 面板材质：none / matte / liquid / acrylic。
     * 取代 v0.05 的 `glassEnabled` 布尔——那一档表达力不够，磨砂 / 液态 / 亚克力全被压成同一个半透明。
     */
    val backMaterial: BackMaterial = BackMaterial.DEFAULT,
    /** 面板强度（不透明度）0.45f-1f，只对 matte / liquid 生效。 */
    val glassAlpha: Float = 0.82f,
    /** 背景雾化半径（Android 12 以下自动降级为 0）；材质自带的下限在 [materialPaint] 里取大。 */
    val glassBlur: Dp = 0.dp,
) {
    /** 气泡主圆角：紧凑模式压到 12dp，其余一律跟用户设的 cornerRadius 走。 */
    fun bubbleRadius(isMine: Boolean): Dp = if (compact) 12.dp else cornerRadius

    /** 靠头像那一侧的尖角：主圆角的 1/3（18 → 6），跟着 cornerRadius 一起缩放。 */
    fun tailRadius(isMine: Boolean): Dp = bubbleRadius(isMine) / 3
}

/**
 * QQ-style palette from the reference mockup: flat surfaces, hairline dividers,
 * 3-level text hierarchy, one saturated accent.
 */
data class QqPalette(
    val bg: Color,
    val card: Color,
    val line: Color,
    val hover: Color,
    val t1: Color,
    val t2: Color,
    val t3: Color,
    val accent: Color,
    val accent2: Color,
    val red: Color,
    val green: Color,
    val amber: Color,
    val dark: Boolean,
) {
    companion object {
        fun light(accent: Color, accent2: Color) = QqPalette(
            bg = Color(0xFFF5F7FA),
            card = Color(0xFFFFFFFF),
            line = Color(0xFFEAEEF4),
            hover = Color(0xFFF0F4F9),
            t1 = Color(0xFF0D1117),
            t2 = Color(0xFF6B7684),
            t3 = Color(0xFFA3ADBA),
            accent = accent,
            accent2 = accent2,
            red = Color(0xFFFF3B30),
            green = Color(0xFF22C55E),
            amber = Color(0xFFF59E0B),
            dark = false,
        )

        fun dark(accent: Color, accent2: Color) = QqPalette(
            bg = Color(0xFF0E1116),
            card = Color(0xFF171C24),
            line = Color(0xFF232A35),
            hover = Color(0xFF1E242E),
            t1 = Color(0xFFE8EDF5),
            t2 = Color(0xFF8B96A6),
            t3 = Color(0xFF5D6878),
            accent = accent,
            accent2 = accent2,
            red = Color(0xFFFF453A),
            green = Color(0xFF22C55E),
            amber = Color(0xFFF59E0B),
            dark = true,
        )
    }
}

val LocalThemeTokens = staticCompositionLocalOf { ThemeTokens() }
val LocalQq = staticCompositionLocalOf { QqPalette.light(Color(0xFF0099FF), Color(0xFF3CB4FF)) }

/**
 * 卡片 / 面板这类「主要容器」读的外观参数。想给卡片描边或换成玻璃质感时读它，
 * 别再往 ThemeTokens 里加字段：Theme.kt 之外的组件只该看到这三个数。
 */
data class SurfaceStyle(
    val borderWidth: Dp = 0.dp,
    /** 当前材质，卡片按它决定要不要画高光 / 噪点。 */
    val material: BackMaterial = BackMaterial.NONE,
    /** 面板不透明度（= palette.card 的 alpha），需要在底色之外单独拿到的场合读它。 */
    val panelAlpha: Float = 1f,
    /** 高光（液态）/ 细噪点（亚克力）叠层；null = 该材质不画。 */
    val sheen: Brush? = null,
    /** 边缘折射描边；null = 沿用 palette.line。 */
    val edge: Brush? = null,
    val blurRadius: Dp = 0.dp,
)

val LocalSurfaceStyle = staticCompositionLocalOf { SurfaceStyle() }

/** 色相 → 主色/辅色（固定饱和与明度，保证任意色相都够深、够对比）。 */
internal fun hueAccent(hue: Float, light: Boolean): Color {
    val h = ((hue % 360f) + 360f) % 360f
    return if (light) Color.hsv(h, 0.86f, 0.92f) else Color.hsv(h, 0.72f, 1f)
}

private fun lightScheme(p: QqPalette) = lightColorScheme(
    primary = p.accent,
    onPrimary = Color.White,
    primaryContainer = p.accent.copy(alpha = 0.12f),
    onPrimaryContainer = p.accent,
    secondary = p.accent,
    // M3 的容器色不填就落回基线主题（紫灰 #E6E0E9），默认 Card / 输入框会发紫，
    // 和 QQ 风的纯白卡片打架；这里把它们全部对齐到调色板。
    secondaryContainer = p.hover,
    onSecondaryContainer = p.t1,
    tertiary = p.accent2,
    tertiaryContainer = p.hover,
    onTertiaryContainer = p.t1,
    surface = p.card,
    onSurface = p.t1,
    surfaceVariant = p.bg,
    onSurfaceVariant = p.t2,
    surfaceTint = p.accent,
    surfaceContainerLowest = p.card,
    surfaceContainerLow = p.card,
    surfaceContainer = p.card,
    surfaceContainerHigh = p.hover,
    surfaceContainerHighest = p.hover,
    inverseSurface = p.t1,
    inverseOnSurface = p.card,
    background = p.bg,
    onBackground = p.t1,
    outline = p.line,
    outlineVariant = p.line,
    error = p.red,
)

private fun darkScheme(p: QqPalette) = darkColorScheme(
    primary = p.accent,
    onPrimary = Color.White,
    primaryContainer = p.accent.copy(alpha = 0.22f),
    onPrimaryContainer = p.accent2,
    secondary = p.accent,
    secondaryContainer = p.hover,
    onSecondaryContainer = p.t1,
    tertiary = p.accent2,
    tertiaryContainer = p.hover,
    onTertiaryContainer = p.t1,
    surface = p.card,
    onSurface = p.t1,
    surfaceVariant = p.bg,
    onSurfaceVariant = p.t2,
    surfaceTint = p.accent,
    surfaceContainerLowest = p.card,
    surfaceContainerLow = p.card,
    surfaceContainer = p.card,
    surfaceContainerHigh = p.hover,
    surfaceContainerHighest = p.hover,
    inverseSurface = p.t1,
    inverseOnSurface = p.card,
    background = p.bg,
    onBackground = p.t1,
    outline = p.line,
    outlineVariant = p.line,
    error = p.red,
)

private fun typography(scale: Float) = Typography(
    headlineSmall = TextStyle(fontSize = 20.sp * scale, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 17.sp * scale, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.5.sp * scale, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp * scale, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp * scale),
    bodyMedium = TextStyle(fontSize = 14.sp * scale),
    bodySmall = TextStyle(fontSize = 12.5.sp * scale),
    labelMedium = TextStyle(fontSize = 12.sp * scale, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp * scale),
)

/** 解码长边上限：超过就按 inSampleSize 降采样，绝不全尺寸解码。 */
private const val MAX_DECODE_EDGE = 1600

/**
 * 壁纸 / 图标的降采样率（2 的幂）。
 *
 * 不用 [computeInSampleSize]：那个函数的语义是「解码后**不小于**目标」，对铺满全屏的壁纸
 * 会退化成 sample=1（例如 4032×3024 对 1600 的目标算出 1），等于全尺寸解码 49MB 位图。
 * 这里要的是「解码后**尽量不超过**上限」，所以按长边比值取最高位。
 */
internal fun wallpaperSampleSize(longestEdge: Int, maxEdge: Int = MAX_DECODE_EDGE): Int =
    if (longestEdge <= maxEdge || maxEdge <= 0) 1
    else Integer.highestOneBit(longestEdge / maxEdge).coerceAtLeast(1)

/**
 * 把壁纸 / 图标读成 ImageBitmap。两种来源都认：
 * - `content://`（SAF 直接给的 uri）；
 * - 绝对路径（设置页选图时复制进 filesDir/appearance 的那一份，见 AppearanceImages）。
 *
 * 以前是「decodeStream 全尺寸 + runCatching 吞异常」：一张 4K 照片解码出来 ≈30MB 位图，
 * OOM 被吞成 null，界面静默回落到纯色 / 🐳 —— 看起来就是「换了图没反应」。现在先读
 * bounds 再降采样，任何失败（uri 失效、权限被回收、不是图片）仍静默回退 null。
 */
@Composable
private fun rememberBackgroundBitmap(ref: String): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, ref) {
        value = if (ref.isBlank()) {
            null
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                decodeImageBitmap(context, ref)?.asImageBitmap()
            }
        }
    }
    return bitmap
}

private fun openImageStream(context: Context, ref: String): InputStream? = runCatching {
    val uri = Uri.parse(ref)
    when {
        uri.scheme == null || uri.scheme == "file" ->
            uri.path?.let { path -> File(path).takeIf { it.isFile }?.inputStream() }
        else -> context.contentResolver.openInputStream(uri)
    }
}.getOrNull()

private fun decodeImageBitmap(context: Context, ref: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openImageStream(context, ref)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val sample = wallpaperSampleSize(maxOf(bounds.outWidth, bounds.outHeight))
    openImageStream(context, ref)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) })
    }
}.getOrNull()

/**
 * 玻璃 / 壁纸生效后的调色板：
 * - 有壁纸时 bg 变半透明，否则整屏纯色会把图片彻底盖住；
 * - 开了玻璃时 card / hover 按 glassAlpha 半透明，让壁纸从面板底下透出来；
 * - 边框宽度不进调色板（它是尺寸不是颜色），走 [LocalSurfaceStyle]。
 */
private fun QqPalette.withAppearance(tokens: ThemeTokens, hasWallpaper: Boolean): QqPalette {
    if (tokens.backMaterial == BackMaterial.NONE) return this
    val paint = materialPaint(tokens.backMaterial, tokens.glassAlpha, hasWallpaper)
    return copy(
        bg = bg.copy(alpha = paint.pageAlpha),
        card = card.copy(alpha = paint.panelAlpha),
        hover = hover.copy(alpha = paint.panelAlpha),
        line = line.copy(alpha = paint.lineAlpha),
    )
}

/**
 * 雾化半径：只跟用户滑块走，材质不再压下限（否则选亚克力就等于把背景糊 10dp，
 * 看起来像「背景图不对」）。没有壁纸时仍然是 0——糊一层纯色没有意义。
 * 雾化本身只在 Android 12+ 有 RenderEffect；低版本直接 0，只保留半透明降级。
 */
private fun effectiveBlur(tokens: ThemeTokens, hasWallpaper: Boolean): Dp {
    if (!hasWallpaper || tokens.backMaterial == BackMaterial.NONE) return 0.dp
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0.dp
    return maxOf(materialPaint(tokens.backMaterial, tokens.glassAlpha, true).blur, tokens.glassBlur)
}

@Composable
fun DshTheme(
    tokens: ThemeTokens,
    dark: Boolean = tokens.dark || isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val accent = if (tokens.accentHue >= 0f) hueAccent(tokens.accentHue, !dark) else tokens.accent
    val accent2 = if (tokens.accentHue >= 0f) hueAccent(tokens.accentHue + 16f, !dark) else tokens.accent2
    val wallpaper = rememberBackgroundBitmap(tokens.backgroundUri)
    // 「无材质」= 页面实底，壁纸不参与绘制；其余三档才让壁纸透出来。
    val showWallpaper = wallpaper != null && tokens.backMaterial != BackMaterial.NONE
    val paint = materialPaint(tokens.backMaterial, tokens.glassAlpha, showWallpaper)
    val palette = (if (dark) QqPalette.dark(accent, accent2) else QqPalette.light(accent, accent2))
        .withAppearance(tokens, showWallpaper)
    val blurRadius = effectiveBlur(tokens, showWallpaper)
    CompositionLocalProvider(
        LocalThemeTokens provides tokens.copy(dark = dark, accent = accent, accent2 = accent2),
        LocalQq provides palette,
        LocalSurfaceStyle provides SurfaceStyle(
            borderWidth = tokens.borderWidth,
            material = tokens.backMaterial,
            panelAlpha = paint.panelAlpha,
            sheen = paint.sheen,
            edge = paint.edge,
            blurRadius = blurRadius,
        ),
    ) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(palette) else lightScheme(palette),
            typography = typography(tokens.fontScale),
        ) {
            if (!showWallpaper) {
                content()
            } else {
                Box(Modifier.fillMaxSize()) {
                    if (wallpaper != null) {
                        val imageModifier = if (blurRadius > 0.dp) {
                            Modifier.fillMaxSize().blur(blurRadius)
                        } else {
                            Modifier.fillMaxSize()
                        }
                        Image(
                            bitmap = wallpaper,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = imageModifier,
                        )
                    }
                    // 压暗只在真的画了壁纸时叠；材质选「无」时不再留一层莫名其妙的黑罩。
                    if (tokens.backgroundDim > 0f) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = tokens.backgroundDim.coerceIn(0f, 0.8f))))
                    }
                    content()
                }
            }
        }
    }
}

/**
 * 应用图标：`appIconStyle = "custom"` 且图片可读时直接画那张图，否则回落到内置黑白 H。
 * 抽屉头部与外观预览共用，改图标样式两处同步变。
 */
@Composable
fun AppIconBadge(size: Dp = 52.dp, modifier: Modifier = Modifier) {
    val t = LocalThemeTokens.current
    val p = LocalQq.current
    val image = rememberBackgroundBitmap(if (t.appIconStyle == "custom") t.customIconUri else "")
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            // Fit 而不是 Crop：用户选的图标往往不是正方形（logo 带字、横版图），
            // Crop 会把左右/上下切掉，看起来就是「图标不对」。
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 内置图标：黑底白色 H，和启动器里那张自适应图标一致。
            Canvas(Modifier.fillMaxSize()) {
                val w = this.size.width
                val h = this.size.height
                val barW = w * 0.14f
                val top = h * 0.26f
                val bottom = h * 0.74f
                val midH = h * 0.14f
                drawRect(Color.White, topLeft = Offset(w * 0.30f, top), size = Size(barW, bottom - top))
                drawRect(Color.White, topLeft = Offset(w * 0.56f, top), size = Size(barW, bottom - top))
                drawRect(Color.White, topLeft = Offset(w * 0.30f, (top + bottom) / 2 - midH / 2), size = Size(w * 0.40f, midH))
            }
        }
    }
}
