package dev.dsh.mobile.core.data.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * engine:// 端点绝不进 java.net.URL：入口就给可读失败，不联网、不抛协议层天书。
 */
class ModelCatalogEngineTest {

    @Test
    fun engineSchemeIsRejectedWithActionableMessage() = runTest {
        val error = ModelCatalog.fetch("engine://", "").exceptionOrNull()
        assertTrue("应是 IllegalArgumentException，实际 $error", error is IllegalArgumentException)
        assertEquals(
            "engine:// 端点由引擎插件提供，模型列表走宿主引擎通道（对话框里的「拉取模型列表」会自动用这条通道）",
            error?.message,
        )
    }

    @Test
    fun engineSchemeWithModelIdIsRejectedToo() = runTest {
        val error = ModelCatalog.fetch("engine://deepseek-v4.1-flash", "sk-test").exceptionOrNull()
        assertTrue("应是 IllegalArgumentException，实际 $error", error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("引擎通道"))
    }

    @Test
    fun failureTextNeverLeaksProtocolJargon() {
        listOf("engine://", "engine://deepseek-v4.1-flash", "ftp://host/v1", "localhost:8080").forEach { url ->
            val msg = ModelCatalog.schemeRejection(url).orEmpty()
            assertFalse("$url → $msg", msg.contains("unknown protocol", ignoreCase = true))
            assertFalse("$url → $msg", msg.contains("MalformedURL", ignoreCase = true))
            assertTrue("$url 应有可读原因", msg.isNotBlank())
        }
    }

    @Test
    fun httpSchemesPassTheGate() {
        assertNull(ModelCatalog.schemeRejection("https://api.deepseek.com"))
        assertNull(ModelCatalog.schemeRejection("http://127.0.0.1:8787/v1"))
        assertNull(ModelCatalog.schemeRejection("  HTTPS://api.deepseek.com  "))
    }

    @Test
    fun engineEndpointDetectionDrivesTheDialogBranch() {
        assertTrue(ModelCatalog.isEngineEndpoint("engine://"))
        assertTrue(ModelCatalog.isEngineEndpoint("  ENGINE://deepseek-v4.1-flash "))
        assertFalse(ModelCatalog.isEngineEndpoint("https://api.deepseek.com"))
        assertFalse(ModelCatalog.isEngineEndpoint(""))
    }
}
