package dev.dsh.mobile.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 几何令牌的读取入口：形状 / 间距 / 描边 / 字号。
 *
 * 以前用户设置里的 cornerRadius / density / compact / fontScale 改了页面毫无反应——
 * 每个组件都写死 `RoundedCornerShape(12.dp)`、`padding(16.dp)`，设置只落到 [ThemeTokens]
 * 里没人读。这里把「读令牌」收敛成四个 object/函数，组件只跟它们打交道。
 *
 * 全部从 CompositionLocal 读，没有任何常量兜底：默认值的一致性由 [ThemeTokens] 负责。
 */
object QqShape {

    /** 卡片 / 面板 / 分段容器：直接用用户的 cornerRadius。 */
    @Composable
    @ReadOnlyComposable
    fun card(): RoundedCornerShape = RoundedCornerShape(LocalThemeTokens.current.cornerRadius)

    /** 输入框 / 表单行 / 搜索框：cornerRadius 的 0.75 倍。 */
    @Composable
    @ReadOnlyComposable
    fun field(): RoundedCornerShape = RoundedCornerShape(LocalThemeTokens.current.fieldRadius)

    /** 芯片 / 标签 / 小徽章：cornerRadius 的 0.625 倍。 */
    @Composable
    @ReadOnlyComposable
    fun chip(): RoundedCornerShape = RoundedCornerShape(LocalThemeTokens.current.chipRadius)

    /** 底部抽屉 / 大面板：cornerRadius 的 1.25 倍。 */
    @Composable
    @ReadOnlyComposable
    fun sheet(): RoundedCornerShape = RoundedCornerShape(LocalThemeTokens.current.sheetRadius)

    /** 全圆角（饼形按钮 / 圆点）。50% 不是一个令牌，所以不可组合、无状态。 */
    fun pill(): RoundedCornerShape = RoundedCornerShape(50)
}

/**
 * 间距阶：4 / 8 / 12 / 16 / 20dp，乘「列表密度」[ThemeTokens.density]，
 * 紧凑模式 [ThemeTokens.compact] 再整体打 0.82 折，最后取整到 1dp（半 dp 间距在
 * 手机上只会让基线漂移）。**只作用于间距**，不要拿它乘字号——字号走 [qqFontBody]。
 */
object QqSpace {
    @Composable
    @ReadOnlyComposable
    fun xs(): Dp = spaced(4.dp)

    @Composable
    @ReadOnlyComposable
    fun s(): Dp = spaced(8.dp)

    @Composable
    @ReadOnlyComposable
    fun m(): Dp = spaced(12.dp)

    @Composable
    @ReadOnlyComposable
    fun l(): Dp = spaced(16.dp)

    @Composable
    @ReadOnlyComposable
    fun xl(): Dp = spaced(20.dp)
}

@Composable
@ReadOnlyComposable
private fun spaced(base: Dp): Dp {
    val t = LocalThemeTokens.current
    val factor = if (t.compact) 0.82f else 1f
    return (base.value * t.density * factor).roundToInt().dp
}

/** 卡片 / 面板描边宽度：外观设置里的 border_width，无材质无描边时就是 0dp。 */
@Composable
@ReadOnlyComposable
fun qqBorder(): Dp = LocalSurfaceStyle.current.borderWidth

/** 正文字号：字面 base 乘 [FontRoles.body] 与 [ThemeTokens.fontScale]。 */
@Composable
@ReadOnlyComposable
fun qqFontBody(base: TextUnit): TextUnit = scaledFont(base, LocalFontRoles.current.body)

/** 代码字号：字面 base 乘 [FontRoles.code] 与 [ThemeTokens.fontScale]。 */
@Composable
@ReadOnlyComposable
fun qqFontCode(base: TextUnit): TextUnit = scaledFont(base, LocalFontRoles.current.code)

@Composable
@ReadOnlyComposable
private fun scaledFont(base: TextUnit, roleScale: Float): TextUnit =
    (base.value * roleScale * LocalThemeTokens.current.fontScale).sp
