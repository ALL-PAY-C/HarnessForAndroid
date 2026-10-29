package dev.dsh.mobile.core.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun info(code: Long) = UpdateInfo(
    versionCode = code,
    versionName = "9.$code",
    apkUrl = "https://example.invalid/dsh.apk",
)

class UpdatePromptPolicyTest {

    /** null：清单没拉到 / 拉到了但解析失败，一律不弹。 */
    @Test
    fun nullInfoNeverPrompts() {
        assertFalse(shouldPromptUpdate(null, 0L))
        assertFalse(shouldPromptUpdate(null, 81L))
        assertFalse(shouldPromptUpdateOnce(null, 0L, 0L))
        assertFalse(shouldPromptUpdateOnce(null, 81L, 999L))
    }

    /** 相等：已是最新版，不弹，哪怕从来没弹过。 */
    @Test
    fun equalVersionDoesNotPrompt() {
        assertFalse(shouldPromptUpdate(info(81), 81L))
        assertFalse(shouldPromptUpdateOnce(info(81), 81L, 0L))
        assertFalse(shouldPromptUpdateOnce(info(81), 81L, 81L))
    }

    /** 更大：正常提示；没弹过或只弹过旧版本都算该弹。 */
    @Test
    fun newerVersionPrompts() {
        assertTrue(shouldPromptUpdate(info(82), 81L))
        assertTrue(shouldPromptUpdateOnce(info(82), 81L, 0L))
        assertTrue(shouldPromptUpdateOnce(info(82), 81L, 81L))
    }

    /** 更小：清单回滚或脏数据，不提示降级。 */
    @Test
    fun olderVersionDoesNotPrompt() {
        assertFalse(shouldPromptUpdate(info(80), 81L))
        assertFalse(shouldPromptUpdateOnce(info(80), 81L, 0L))
    }

    /** 已提示过：同一版本闭嘴，版本号涨了才重新打扰。 */
    @Test
    fun alreadyPromptedVersionIsSkipped() {
        assertFalse(shouldPromptUpdateOnce(info(82), 81L, 82L))
        assertFalse(shouldPromptUpdateOnce(info(82), 81L, 83L))
        assertTrue(shouldPromptUpdateOnce(info(83), 81L, 82L))
    }
}
