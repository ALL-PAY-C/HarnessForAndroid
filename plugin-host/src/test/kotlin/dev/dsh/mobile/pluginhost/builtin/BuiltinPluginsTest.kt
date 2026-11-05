package dev.dsh.mobile.pluginhost.builtin

import android.content.ContextWrapper
import dev.dsh.mobile.pluginapi.BackgroundPlugin
import dev.dsh.mobile.pluginapi.ConfigFieldType
import dev.dsh.mobile.pluginapi.PluginRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3-0 接缝：两个内置插件的清单、模型源、设置项与幂等拉起。
 *
 * 一行都不碰真实网络与真实网关：ensureStarted 现在就是空实现，这里钉的是「宿主以后能靠它做事」
 * ——所以断言集中在清单形态（宿主读 manifest 注册模型源、渲染设置页）与重复调用的稳定性。
 */
class BuiltinPluginsTest {

    private val expected = mapOf(
        LocalGatewayBuiltinPlugin.ID to LocalGatewayBuiltinPlugin.VERSION,
        StreamGatewayBuiltinPlugin.ID to StreamGatewayBuiltinPlugin.VERSION,
    )

    @Test
    fun manifestsCarryExpectedIdAndVersion() {
        assertEquals(2, builtinPlugins.size)
        builtinPlugins.forEach { plugin ->
            val manifest = plugin.manifest
            assertEquals("版本必须是 2.0.0", "2.0.0", manifest.version)
            assertEquals("id 必须与声明一致", expected[manifest.id], manifest.version)
            assertTrue("清单必须有可读名字", manifest.name.isNotBlank())
        }
        assertEquals(setOf("pl.gateway.local", "pl.gateway.stream"), builtinPlugins.map { it.manifest.id }.toSet())
    }

    @Test
    fun eachPluginContributesOneLoopbackModelSource() {
        builtinPlugins.forEach { plugin ->
            // 注册不能抛：注册期异常会被宿主记成「插件加载失败」，整行变灰。
            plugin.register(PluginRegistry())
            val sources = plugin.manifest.modelSources
            assertEquals(plugin.manifest.id + " 必须且只能贡献 1 条模型源", 1, sources.size)
            // 模型源是清单声明的：PluginHost.modelSources() 直接读 manifest，registry 没有对应集合。
            val baseUrl = sources.single().baseUrl
            assertTrue(
                plugin.manifest.id + " 的 baseUrl 必须是本机回环，实际=" + baseUrl,
                baseUrl.startsWith("http://127.0.0.1:"),
            )
            assertTrue("baseUrl 必须带 /v1 后缀", baseUrl.endsWith("/v1"))
        }
    }

    @Test
    fun everyManifestDeclaresSecretToken() {
        builtinPlugins.forEach { plugin ->
            val settings = plugin.manifest.settings
            assertTrue(plugin.manifest.id + " 至少要有 3 个设置项", settings.size >= 3)
            val token = settings.firstOrNull { it.key == GatewaySettings.KEY_TOKEN }
            assertTrue(plugin.manifest.id + " 必须声明鉴权令牌设置项", token != null)
            assertEquals(
                "鉴权令牌必须是 Secret，否则设置页会把它明文显示",
                ConfigFieldType.Secret,
                token!!.type,
            )
        }
    }

    @Test
    fun ensureStartedIsIdempotentSuccess() = runBlocking {
        // ContextWrapper(null)：桩 Context 没有 SharedPreferences，网关按「未填凭据」启动 ——
        // 这正是要钉的语义：缺配置也要起得来，缺什么由 HTTP 响应告诉用户，不是靠失败重拉。
        // 端口注入成高位端口：8318/8319 在真机上各有主人，单测不能去抢。
        val plugin = LocalGatewayBuiltinPlugin { 18_399 }
        val context = ContextWrapper(null)
        repeat(3) { round ->
            val result = plugin.ensureStarted(context)
            assertTrue(
                "第 " + (round + 1) + " 次拉起必须成功：" + result.exceptionOrNull(),
                result.isSuccess,
            )
        }
    }

    @Test
    fun everyBuiltinSurvivesRepeatedStartWithoutThrowing() = runBlocking {
        // 桩 Context 上的成败由各插件自己决定（pl.gateway.stream 读不到配置就返回 failure），
        // 这里只钉宿主契约：不抛异常。守护协程靠 Result 决策，抛异常会掀掉整轮探测。
        val context = ContextWrapper(null)
        listOf(LocalGatewayBuiltinPlugin { 18_399 }, StreamGatewayBuiltinPlugin { 18_400 }).forEach { plugin ->
            repeat(3) { round ->
                val result = runCatching { (plugin as BackgroundPlugin).ensureStarted(context) }
                assertTrue(
                    plugin.manifest.id + " 第 " + (round + 1) + " 次拉起抛了异常：" + result.exceptionOrNull(),
                    result.isSuccess,
                )
            }
        }
    }

    @Test
    fun portIsInjectedIntoManifestBaseUrl() {
        val plugin = LocalGatewayBuiltinPlugin { 18_317 }
        assertEquals("http://127.0.0.1:18317/v1", plugin.manifest.modelSources.single().baseUrl)
        val stream = StreamGatewayBuiltinPlugin { 18_318 }
        assertEquals("http://127.0.0.1:18318/v1", stream.manifest.modelSources.single().baseUrl)
    }
}
