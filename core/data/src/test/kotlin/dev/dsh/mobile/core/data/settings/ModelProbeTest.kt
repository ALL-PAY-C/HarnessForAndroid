package dev.dsh.mobile.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「测试连接」的纯函数部分：结果格式化 + 模型列表解析。不联网。 */
class ModelProbeTest {

    @Test
    fun successWithModelsIsFormattedForSnackbar() {
        val text = ModelProbe.formatResult(
            ProbeResult(ok = true, elapsedMs = 320, models = listOf("deepseek-chat", "deepseek-reasoner")),
        )
        assertEquals("成功 · 320ms · 模型 deepseek-chat,deepseek-reasoner", text)
    }

    @Test
    fun successWithoutModelsHasNoModelSuffix() {
        val text = ModelProbe.formatResult(ProbeResult(ok = true, elapsedMs = 88))
        assertEquals("成功 · 88ms", text)
    }

    @Test
    fun httpFailureShowsStatusCode() {
        val text = ModelProbe.formatResult(
            ProbeResult(ok = false, elapsedMs = 120, httpCode = 401, error = "invalid api key"),
        )
        assertEquals("失败 · HTTP 401", text)
    }

    @Test
    fun transportFailureShowsReason() {
        val text = ModelProbe.formatResult(ProbeResult(ok = false, elapsedMs = 6_000, error = "连接超时（6000ms）"))
        assertEquals("失败 · 连接超时（6000ms）", text)
    }

    @Test
    fun unknownFailureDoesNotEmitEmptyReason() {
        assertEquals("失败 · 未知错误", ModelProbe.formatResult(ProbeResult(ok = false, elapsedMs = 1)))
    }

    @Test
    fun parsesOpenAiModelListShapes() {
        assertEquals(
            listOf("a", "b"),
            ModelProbe.parseModelIds("""{"data":[{"id":"b"},{"id":"a"}]}"""),
        )
        assertEquals(
            listOf("m1", "m2"),
            ModelProbe.parseModelIds("""{"models":[{"name":"m2"},{"id":"m1"}]}"""),
        )
        assertEquals(listOf("x", "y"), ModelProbe.parseModelIds("""{"data":["y","x"]}"""))
        assertTrue(ModelProbe.parseModelIds("not json").isEmpty())
        assertTrue(ModelProbe.parseModelIds("").isEmpty())
    }
}
