package dev.dsh.mobile.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 全文件访问条目何时显示（文案在 UI 层由资源决定）。 */
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
}
