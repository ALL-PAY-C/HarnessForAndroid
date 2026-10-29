package dev.dsh.mobile.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.core.model.BackMaterial

/**
 * 材质落到三个 alpha + 两个 brush 上的唯一算法（页面 / 面板 / 描边 / 叠层）。
 *
 * 页面底色的 alpha 决定壁纸透多少；卡片 / 内嵌面板都靠它跟页面拉开层次，改动前先看
 * 全项目 42 处 `background(p.bg)`。有壁纸时这一层只是染色，不是遮罩——数值越低壁纸越清楚。
 *
 * 材质不再自带模糊下限：雾化只由用户滑块决定。以前液态固定 3dp、亚克力固定 10dp，
 * 选了亚克力的人看到的就是一张糊掉的背景，还找不到是谁糊的。
 */
data class MaterialPaint(
    /** 页面底色（bg）不透明度。1f = 实底，壁纸看不见。 */
    val pageAlpha: Float,
    /** 卡片 / 面板（card、hover）不透明度。 */
    val panelAlpha: Float,
    /** 分割线 / 描边不透明度。与「强度」解耦，边框设置才不会被玻璃吃掉。 */
    val lineAlpha: Float,
    /** 材质自带的雾化下限；现在恒为 0，雾化只跟用户的滑块走。 */
    val blur: Dp,
    /** 面板高光（液态）或细噪点（亚克力）叠层：画在面板底色之上、文字之下。 */
    val sheen: Brush?,
    /** 边缘折射描边；null = 沿用 palette 的分割线色。 */
    val edge: Brush?,
)

// 壁纸是背景本身，页面这层只做染色。0.34 那档太淡，浅色壁纸配深色主题时白字直接糊在图上；
// 0.78 那档又闷成一片。每个材质给一个底，再按「强度」滑块加最多 0.35 的实心度：
// 拖到最大页面接近实底（图淡），拖到最小图最清楚。
// 卡片不参与这一层，恒为实底（见下面 panel 的说明）。
private const val MATTE_PAGE_ALPHA = 0.60f
private const val LIQUID_PAGE_ALPHA = 0.52f
private const val ACRYLIC_PAGE_ALPHA = 0.85f
private const val STRENGTH_SWING = 0.35f

fun materialPaint(material: BackMaterial, strength: Float, hasWallpaper: Boolean): MaterialPaint {
    val s = BackMaterial.clampStrength(strength)
    // 没有壁纸时页面底色必须保持不透明，否则整屏只是把窗口后面的黑透出来（发灰）。
    // 有壁纸时 base 是「强度最小」的那档，强度越大越实。
    fun page(base: Float) = if (hasWallpaper) (base + STRENGTH_SWING * s).coerceAtMost(1f) else 1f
    // 卡片一律实底：半透卡片压在任何底色上都是一层灰雾——白底主题下发灰，深色主题压在浅壁纸上白字直接糊掉。
    // 玻璃感只留在页面底色那一层（page），卡片不再参与透明度。
    return when (material) {
        // 无材质：页面与面板全实底，描边原样。
        BackMaterial.NONE -> MaterialPaint(1f, 1f, 1f, 0.dp, null, null)

        // 磨砂：强度滑块只影响页面那层（越强越实），卡片恒为实底。
        BackMaterial.MATTE -> MaterialPaint(page(MATTE_PAGE_ALPHA), 1f, 0.55f, 0.dp, null, null)

        // 液态：页面比磨砂更透，顶部一团高光 + 亮边折射给玻璃感；卡片仍实底。
        BackMaterial.LIQUID -> MaterialPaint(
            pageAlpha = page(LIQUID_PAGE_ALPHA),
            panelAlpha = 1f,
            lineAlpha = 0.42f,
            blur = 0.dp,
            sheen = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.20f),
                    Color.White.copy(alpha = 0.05f),
                    Color.Transparent,
                ),
            ),
            edge = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.34f),
                    Color.White.copy(alpha = 0.06f),
                    Color.White.copy(alpha = 0.12f),
                ),
            ),
        )

        // 亚克力：不透明度反而拉高（更实），叠一层 45° 细条纹近似噪点。
        // 强度滑块只管 matte / liquid，这里用常量，避免「拖了没反应」的错觉。
        BackMaterial.ACRYLIC -> MaterialPaint(
            pageAlpha = if (hasWallpaper) ACRYLIC_PAGE_ALPHA else 1f,
            panelAlpha = 1f,
            lineAlpha = 0.7f,
            blur = 0.dp,
            sheen = Brush.linearGradient(
                colors = List(12) { i ->
                    if (i % 2 == 0) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
                },
                start = Offset.Zero,
                end = Offset.Infinite,
            ),
            edge = null,
        )
    }
}

/**
 * 面板材质叠层。加在卡片底色之后、内容之前：drawBehind 只画在内容背后，
 * 所以 `.background(p.card).panelSheen()` 的层序是 底色 → 高光 → 文字。
 */
@Composable
fun Modifier.panelSheen(): Modifier {
    val sheen = LocalSurfaceStyle.current.sheen ?: return this
    return drawBehind { drawRect(brush = sheen) }
}
