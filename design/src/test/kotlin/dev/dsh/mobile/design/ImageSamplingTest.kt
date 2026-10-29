package dev.dsh.mobile.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** H7 降采样纯逻辑测试：computeInSampleSize 必须 2 的幂、解码后不小于目标尺寸、非法尺寸回退 1。 */
class ImageSamplingTest {

    @Test
    fun noDownsampleWhenImageFitsTarget() {
        assertEquals(1, computeInSampleSize(100, 100, 200, 200))
    }

    @Test
    fun downsamplesByPowerOfTwo() {
        // 4096x3072 -> 目标 1024x768：sample=4，解码结果正好 1024x768。
        assertEquals(4, computeInSampleSize(4096, 3072, 1024, 768))
    }

    @Test
    fun decodedResultNeverSmallerThanTarget() {
        // 3000x3000 -> 目标 1000x1000：sample=2 -> 1500x1500（>= 目标），不能取 4 变成 750。
        assertEquals(2, computeInSampleSize(3000, 3000, 1000, 1000))
    }

    @Test
    fun invalidDimensionsFallBackToOne() {
        assertEquals(1, computeInSampleSize(0, 0, 100, 100))
        assertEquals(1, computeInSampleSize(-1, 100, 100, 100))
    }

    @Test
    fun twelveMegapixelPhotoDownsamplesWithRealDisplayTarget() {
        // 4032x3024（约 12MP）对缩略图真实显示目标 1320px（220dp@3x × 2 过采样）必须 sample>1，
        // 否则整图 ARGB ~49MB，OOM 风险。回归 559 复审的「整屏目标导致 12MP sample=1」。
        val sample = computeInSampleSize(4032, 3024, 1320, 1320)
        assertTrue("12MP 图对真实显示目标必须降采样，实际 sample=$sample", sample > 1)
        assertEquals(2, sample)
    }
}
