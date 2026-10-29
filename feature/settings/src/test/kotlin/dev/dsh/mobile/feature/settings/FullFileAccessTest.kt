package dev.dsh.mobile.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 全文件访问条目的两处纯逻辑：何时显示、显示什么。 */
class FullFileAccessTest {

    @Test
    fun hiddenBeforeAndroid11() {
        assertNull(fullFileAccessAction(29))
        assertNull(fullFileAccessAction(26))
    }

    @Test
    fun pointsAtAllFilesPageOnAndroid11AndUp() {
        assertEquals("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION", fullFileAccessAction(30))
        assertEquals("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION", fullFileAccessAction(35))
    }

    @Test
    fun labelDistinguishesGrantState() {
        val granted = fullFileAccessLabel(true)
        val denied = fullFileAccessLabel(false)
        assertNotEquals(granted, denied)
        // 未授权时必须告诉用户点哪儿，否则这条目看着像死链。
        assertEquals(true, denied.contains("点击"))
        assertEquals(true, granted.startsWith("已授权"))
    }
}
