package dev.dsh.mobile.feature.plugins

import dev.dsh.mobile.design.R as DesignR
import dev.dsh.mobile.design.UiText
import dev.dsh.mobile.pluginhost.NotLoadedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T9.27：「未加载（原因）」与「加载失败」是两种行 —— 文案分开、配色分开。
 *
 * 配色单独走 [RowTone] 而不是复判 failed：以前安全模式挡住的行只能把策略文案塞进
 * loadError，于是弹出红色「加载失败」并灰掉开关，用户既看不到出路也点不动开关。
 */
class PluginRowToneTest {

    @Test
    fun safeModeShowsNotLoadedTextAndNeutralTone() {
        val row = pluginRowStatus(enabled = false, loadError = null, notLoaded = NotLoadedReason.SafeMode)

        val text = row.text as UiText.Res
        assertEquals(DesignR.string.plugins_row_not_loaded, text.id)
        assertEquals(
            "原因走资源、走参数，不是一段硬编码错误原文",
            UiText.Res(DesignR.string.plugins_reason_safe_mode_short),
            text.args.single(),
        )
        assertFalse("未加载不是失败态", row.failed)
        assertNull("未加载行没有错误原文可展开", row.detail)
        assertEquals(RowTone.NotLoaded, row.tone)
    }

    @Test
    fun engineUnavailableHasItsOwnReason() {
        val row = pluginRowStatus(enabled = false, loadError = null, notLoaded = NotLoadedReason.EngineUnavailable)
        val text = row.text as UiText.Res
        assertEquals(DesignR.string.plugins_row_not_loaded, text.id)
        assertEquals(UiText.Res(DesignR.string.plugins_reason_engine_unavailable), text.args.single())
        assertEquals(RowTone.NotLoaded, row.tone)
    }

    @Test
    fun realFailureStillWinsAndStaysRed() {
        val row = pluginRowStatus(
            enabled = false,
            loadError = "[exec] index.js 执行失败",
            notLoaded = NotLoadedReason.EngineUnavailable,
        )
        assertEquals(DesignR.string.plugins_row_load_failed, (row.text as UiText.Res).id)
        assertEquals("[exec] index.js 执行失败", row.detail)
        assertEquals("真失败压过一切，配色必须是红", RowTone.Failed, row.tone)
    }

    @Test
    fun userDisabledKeepsDisabledRow() {
        val row = pluginRowStatus(enabled = false, loadError = null, notLoaded = NotLoadedReason.Disabled)
        assertEquals(UiText.Res(DesignR.string.plugins_row_disabled), row.text)
        assertEquals(RowTone.Disabled, row.tone)
    }

    @Test
    fun needsPermissionOutranksNotLoaded() {
        val row = pluginRowStatus(
            enabled = false,
            loadError = null,
            needsPermission = true,
            notLoaded = NotLoadedReason.SafeMode,
        )
        assertEquals(DesignR.string.plugins_perm_needs, (row.text as UiText.Res).id)
        assertEquals("缺授权的出路是点「授予」，不能被安全模式文案盖掉", RowTone.NeedsPermission, row.tone)
    }
}
