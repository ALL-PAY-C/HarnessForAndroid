package dev.dsh.mobile.design.startup

import android.net.Uri
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 启动动画的扩展点。
 *
 * 默认不开（spec 为空串），要开就写一条 spec 字符串：
 * - `builtin:fade` / `builtin:draw-h` / `builtin:bounce` —— 内置三种，见下方的 Builtins。
 * - `image:<uri>` —— 任意本地图片；GIF/WebP 动图交给 ImageView 自己播。
 * - `plugin:<id>` —— 第三方注册的样式：插件调用 [StartupAnimations.register] 挂一个
 *   [StartupAnimation] 进来即可，宿主不认识这个 id 就自动退回「不播」。
 * - 裸 id（例如 `fade`）等同于 `builtin:fade`，方便手写。
 *
 * 时长由设置的 [DurationMs] 覆盖，0 表示用样式自己的 [StartupAnimation.defaultDurationMs]。
 */
sealed interface StartupSpec {
    /** 不开动画：启动直接进界面，零额外组合、零额外帧。 */
    data object Off : StartupSpec

    /** 内置样式。 */
    data class Builtin(val id: String) : StartupSpec

    /** 用户选的图片（SAF uri）：静态图或 GIF。 */
    data class Image(val uri: String) : StartupSpec

    /** 第三方注册的样式；猜不到就当作 Off。 */
    data class Plugin(val id: String) : StartupSpec
}

/** 一个启动动画样式：自己画自己的，宿主只负责计时与遮盖。 */
interface StartupAnimation {
    /** 注册表里的唯一 id，spec 里 `builtin:` / `plugin:` 后面那一段就是它。 */
    val id: String

    /** 设置页下拉里显示的名字。 */
    val label: String

    /** 样式自带时长（ms），用户没填时长时用它。 */
    val defaultDurationMs: Int

    /** progress 从 0f 走到 1f，正好覆盖一趟启动。 */
    @Composable
    fun Draw(progress: Float)
}

/** 样式的注册表：内置的在下面 init 里挂好，第三方随时可以加。 */
object StartupAnimations {

    private val registry = linkedMapOf<String, StartupAnimation>()

    init {
        register(FadeIn)
        register(DrawH)
        register(Bounce)
    }

    /** 注册（或覆盖同名）一个样式；插件卸载时用 [unregister] 摘掉。 */
    fun register(animation: StartupAnimation) {
        registry[animation.id] = animation
    }

    fun unregister(id: String) {
        registry.remove(id)
    }

    fun of(id: String): StartupAnimation? = registry[id]

    /** 内置样式列表，设置页直接拿它渲染选项。 */
    fun builtins(): List<StartupAnimation> = listOf(FadeIn, DrawH, Bounce)

    /** 解析 spec 字符串。空串 / `none` / `off` 都是关。 */
    fun parse(raw: String): StartupSpec {
        val s = raw.trim()
        if (s.isEmpty()) return StartupSpec.Off
        val lower = s.lowercase()
        if (lower == "none" || lower == "off") return StartupSpec.Off
        val colon = s.indexOf(':')
        if (colon <= 0) return StartupSpec.Builtin(s)
        val kind = s.substring(0, colon).lowercase()
        val rest = s.substring(colon + 1).trim()
        if (rest.isEmpty()) return StartupSpec.Off
        return when (kind) {
            "builtin", "b" -> StartupSpec.Builtin(rest)
            "image", "img", "gif" -> StartupSpec.Image(rest)
            "plugin", "ext" -> StartupSpec.Plugin(rest)
            // 不认识的协议头当成内置 id 整串传下去，注册表找不到就不播。
            else -> StartupSpec.Builtin(s)
        }
    }

    /** 把 spec 写回设置字符串。 */
    fun encode(spec: StartupSpec): String = when (spec) {
        StartupSpec.Off -> ""
        is StartupSpec.Builtin -> "builtin:" + spec.id
        is StartupSpec.Image -> "image:" + spec.uri
        is StartupSpec.Plugin -> "plugin:" + spec.id
    }

    /** 实际播放时长：用户填了就用用户的，否则用样式默认值。 */
    fun durationMs(spec: StartupSpec, override: Int): Int {
        if (override > 0) return override.coerceIn(200, 5000)
        val fallback = when (spec) {
            is StartupSpec.Builtin -> of(spec.id)?.defaultDurationMs
            is StartupSpec.Plugin -> of(spec.id)?.defaultDurationMs
            else -> null
        } ?: 900
        return fallback.coerceIn(200, 5000)
    }

    /** 样式在设置页里的显示名；猜不到就用 id。 */
    fun labelOf(spec: StartupSpec): String = when (spec) {
        StartupSpec.Off -> "关闭"
        is StartupSpec.Builtin -> of(spec.id)?.label ?: spec.id
        is StartupSpec.Plugin -> of(spec.id)?.label ?: ("插件：" + spec.id)
        is StartupSpec.Image -> "图片"
    }
}

/**
 * 启动动画宿主：把 [content] 正常铺满，第一次组合时在上层盖一层动画，播完（或点一下）就撤掉。
 *
 * spec 关掉时这里等价于 [content]，不额外组合任何节点，也不启动任何协程。
 */
@Composable
fun StartupAnimationGate(
    spec: String,
    durationMs: Int = 0,
    content: @Composable () -> Unit,
) {
    val parsed = remember(spec) { StartupAnimations.parse(spec) }
    if (parsed is StartupSpec.Off) {
        content()
        return
    }
    val total = StartupAnimations.durationMs(parsed, durationMs)
    // 只在这次进程的首次组合里播一次：配置改了也重播，但页面切换不会反复闪。
    var playing by remember(parsed, total) { mutableStateOf(true) }
    val progress = remember(parsed, total) { Animatable(0f) }
    LaunchedEffect(parsed, total) {
        launch { progress.animateTo(1f, tween(durationMillis = total, easing = LinearEasing)) }
        delay(total.toLong())
        playing = false
    }
    Box(Modifier.fillMaxSize()) {
        content()
        if (playing) {
            // 点一下直接跳过；不给水波纹，启动页上没必要。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { playing = false },
                contentAlignment = Alignment.Center,
            ) {
                when (parsed) {
                    is StartupSpec.Builtin -> {
                        val anim = StartupAnimations.of(parsed.id)
                        if (anim != null) anim.Draw(progress.value) else Unit
                    }
                    is StartupSpec.Plugin -> {
                        val anim = StartupAnimations.of(parsed.id)
                        if (anim != null) anim.Draw(progress.value) else Unit
                    }
                    is StartupSpec.Image -> ImageSpec(parsed.uri, progress.value)
                    StartupSpec.Off -> Unit
                }
            }
        }
    }
}

/** 图片型样式：ImageView 自己播 GIF/WebP 动图，宿主只管缩放与淡入。 */
@Composable
private fun ImageSpec(uri: String, progress: Float) {
    val context = LocalContext.current
    val alpha = (progress * 3f).coerceIn(0f, 1f)
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { view ->
            runCatching { view.setImageURI(Uri.parse(uri)) }
            view.alpha = alpha
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ---------- 内置样式 ----------

/** 淡入：H 标由小到大 + 淡入。 */
private object FadeIn : StartupAnimation {
    override val id = "fade"
    override val label = "淡入"
    override val defaultDurationMs = 900

    @Composable
    override fun Draw(progress: Float) {
        val scale = 0.82f + 0.18f * progress
        val alpha = progress.coerceIn(0f, 1f)
        val color = glyphColor().copy(alpha = alpha)
        Canvas(Modifier.fillMaxSize()) {
            drawH(center = center, side = size.minDimension * 0.34f * scale, color = color)
        }
    }
}

/** 画 H：横梁从左到右长出来，两根竖条先立好。 */
private object DrawH : StartupAnimation {
    override val id = "draw-h"
    override val label = "画 H"
    override val defaultDurationMs = 1100

    @Composable
    override fun Draw(progress: Float) {
        val grow = progress.coerceIn(0f, 1f)
        val color = glyphColor()
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension * 0.34f
            val barW = side * 0.22f
            val barH = side * (0.30f + 0.40f * grow)
            val cx = center.x
            val cy = center.y
            drawRect(color, Offset(cx - side / 2, cy - barH / 2), Size(barW, barH))
            drawRect(color, Offset(cx + side / 2 - barW, cy - barH / 2), Size(barW, barH))
            // 横梁从中间往两边长，grow 到 0.5 时正好接满。
            val cross = (side - barW * 2) * ((grow - 0.35f) / 0.65f).coerceIn(0f, 1f)
            if (cross > 0f) {
                drawRect(
                    color,
                    Offset(cx - cross / 2, cy - barW / 2),
                    Size(cross, barW),
                )
            }
        }
    }
}

/** 弹跳：H 标先落下来再回弹到原位。 */
private object Bounce : StartupAnimation {
    override val id = "bounce"
    override val label = "弹跳"
    override val defaultDurationMs = 1000

    @Composable
    override fun Draw(progress: Float) {
        // 阻尼振荡：1 - e^-6t * cos(10t)，落在 1 附近收住。
        val t = progress.coerceIn(0f, 1f)
        val eased = 1f - kotlin.math.exp(-6f * t) * kotlin.math.cos(10f * t)
        val color = glyphColor()
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension * 0.34f
            val dy = (1f - eased) * size.height * 0.25f
            drawH(center = Offset(center.x, center.y + dy), side = side, color = color)
            // 影子跟着高度变淡，落地才明显。
            val shadowAlpha = (1f - dy / (size.height * 0.25f)).coerceIn(0f, 1f) * 0.25f
            if (shadowAlpha > 0.01f) {
                drawRect(
                    Color.Black.copy(alpha = shadowAlpha),
                    Offset(center.x - side * 0.45f, center.y + side * 0.75f),
                    Size(side * 0.9f, side * 0.10f),
                )
            }
        }
    }
}

/** 画一个 H：两根竖条 + 横梁，比例和启动器图标一致。 */
private fun DrawScope.drawH(center: Offset, side: Float, color: Color) {
    val barW = side * 0.22f
    val top = center.y - side / 2
    val bottom = center.y + side / 2
    drawRect(color, Offset(center.x - side / 2, top), Size(barW, bottom - top))
    drawRect(color, Offset(center.x + side / 2 - barW, top), Size(barW, bottom - top))
    drawRect(color, Offset(center.x - side / 2, center.y - barW / 2), Size(side, barW))
}

@Composable
private fun glyphColor(): Color = MaterialTheme.colorScheme.onBackground
