package dev.dsh.mobile.design

import androidx.compose.ui.unit.dp
import dev.dsh.mobile.core.model.BackMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 四档材质产出的 alpha / 模糊 / 叠层。数值改动（例如把液态调得更透）必须同步改这里的断言。 */
class MaterialPaintTest {

    private val eps = 0.0001f

    @Test
    fun noneIsFullyOpaqueEvenWithWallpaper() {
        val paint = materialPaint(BackMaterial.NONE, 0.6f, hasWallpaper = true)
        assertEquals(1f, paint.pageAlpha, eps)
        assertEquals(1f, paint.panelAlpha, eps)
        assertEquals(1f, paint.lineAlpha, eps)
        assertEquals(0.dp, paint.blur)
        assertNull("无材质不该画高光", paint.sheen)
        assertNull(paint.edge)
    }

    @Test
    fun matteKeepsLegacyGlassLook() {
        val paint = materialPaint(BackMaterial.MATTE, 0.8f, hasWallpaper = true)
        // 有壁纸时页面只是染色：底 0.60 + 强度 0.8 * 0.35 = 0.88。
        assertEquals(0.88f, paint.pageAlpha, eps)
        // 卡片恒实底，不随强度和材质变。
        assertEquals(1f, paint.panelAlpha, eps)
        assertNull(paint.sheen)
    }

    @Test
    fun matteWithoutWallpaperStaysOpaque() {
        // 没有壁纸时页面必须实底，否则整屏透出窗口底色发灰。
        val paint = materialPaint(BackMaterial.MATTE, 0.8f, hasWallpaper = false)
        assertEquals(1f, paint.pageAlpha, eps)
        // 卡片也得实底：半透白卡压在白/浅灰底上就是一坨灰雾，白底主题下最明显。
        assertEquals(1f, paint.panelAlpha, eps)
    }

    @Test
    fun everyMaterialKeepsPanelsOpaqueWithoutWallpaper() {
        BackMaterial.entries.filter { it != BackMaterial.NONE }.forEach { material ->
            val paint = materialPaint(material, 0.6f, hasWallpaper = false)
            assertEquals(material.name + " 无壁纸时面板应不透明", 1f, paint.panelAlpha, eps)
        }
    }

    @Test
    fun liquidIsTranslucentWithSheenAndRefraction() {
        val paint = materialPaint(BackMaterial.LIQUID, 0.8f, hasWallpaper = true)
        // 液态底更低：0.52 + 0.8 * 0.35 = 0.80，比磨砂更透。
        assertEquals(0.80f, paint.pageAlpha, eps)
        assertEquals(1f, paint.panelAlpha, eps)
        assertEquals(0.dp, paint.blur)
        assertNotNull("液态要有顶部高光", paint.sheen)
        assertNotNull("液态要有边缘折射描边", paint.edge)
    }

    @Test
    fun acrylicIsOpaqueButKeepsNoise() {
        val paint = materialPaint(BackMaterial.ACRYLIC, 0.5f, hasWallpaper = true)
        assertEquals(0.85f, paint.pageAlpha, eps)
        assertEquals(1f, paint.panelAlpha, eps)
        assertEquals(0.dp, paint.blur)
        assertNotNull("亚克力要细噪点", paint.sheen)
    }

    @Test
    fun acrylicIgnoresStrength() {
        // 强度滑块只对磨砂 / 液态有定义；亚克力拖了也不该变，避免「拖了没反应」的错觉。
        val low = materialPaint(BackMaterial.ACRYLIC, BackMaterial.MIN_ALPHA, hasWallpaper = true)
        val high = materialPaint(BackMaterial.ACRYLIC, BackMaterial.MAX_ALPHA, hasWallpaper = true)
        assertEquals(low.pageAlpha, high.pageAlpha, eps)
        assertEquals(1f, high.panelAlpha, eps)
    }

    @Test
    fun strengthIsClampedForAllMaterials() {
        // 强度被夹到 [MIN, MAX]；夹住之后页面染色呈单调（越强越实），面板恒实底。
        val low = materialPaint(BackMaterial.MATTE, -5f, true)
        val high = materialPaint(BackMaterial.MATTE, 99f, true)
        assertEquals(0.60f + 0.35f * BackMaterial.MIN_ALPHA, low.pageAlpha, eps)
        assertEquals(0.60f + 0.35f * BackMaterial.MAX_ALPHA, high.pageAlpha, eps)
        assertTrue("强度越大页面越实", high.pageAlpha > low.pageAlpha)
        assertEquals(1f, high.panelAlpha, eps)
    }

    @Test
    fun wallpaperSamplingNeverDecodesFullSizeHugeImages() {
        // 4096 长边对 1600 上限 -> sample 2；8000 -> sample 4。
        assertEquals(2, wallpaperSampleSize(4096))
        assertEquals(4, wallpaperSampleSize(8000))
        assertEquals(1, wallpaperSampleSize(1600))
        assertEquals(1, wallpaperSampleSize(0))
    }
}
