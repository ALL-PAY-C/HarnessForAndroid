package dev.dsh.mobile.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「测试连接」的纯函数部分：模型列表解析。不联网。 */
class ModelProbeTest {

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

    @Test
    fun engineEndpointProbeSkipsNetworkAndMarksOffline() {
        val result = ModelProbe.probeBlocking("engine://local", apiKey = "", model = "m1")
        assertTrue(result.ok)
        assertTrue(result.engineOffline)
        assertEquals(listOf("m1"), result.models)
    }

    @Test
    fun blankBaseUrlFailsWithoutNetwork() {
        val result = ModelProbe.probeBlocking("   ", apiKey = "", model = "m1")
        assertEquals(ProbeFailure.EmptyBaseUrl, result.failure)
        assertTrue(!result.ok)
    }
}
