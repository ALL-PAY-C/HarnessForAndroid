package dev.dsh.mobile.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真实 ESM 插件包的端到端契约：
 *  - package.json 带 "type":"module" 的包能被装进来跑（import / export 都通）；
 *  - import 裸模块 → 报 STAGE_LOAD，错误里点出模块名；
 *  - import ../../ 越界 → 拒绝，绝不读到插件目录外面；
 *  - plugin-api 的二进制兼容别名 HtmlBlockRenderers.setRender 还在。
 */
class JsPluginRuntimeEsmTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "dsh-js-esm-test/" + name + "-" + System.nanoTime())
            .apply { deleteRecursively(); mkdirs() }

    private fun file(dir: File, rel: String, text: String) =
        File(dir, rel).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun esmPackageWithRelativeImportsAndDefaultExportRuns() {
        val dir = tempDir("esm-ok")
        file(dir, "package.json", """{"name":"esm-pack","version":"1.0.0","type":"module","main":"index.js"}""")
        file(
            dir, "index.js",
            """
            import { x } from './lib/a.js';
            import def from './lib/b.js';
            import * as ns from './lib/b.js';

            ctx.registerContribution({
              slot: "app.settings",
              id: "esm",
              label: def.label + x,
              subtitle: ns.default.label,
            });

            export default function activate() { return 1; }
            """.trimIndent(),
        )
        file(dir, "lib/a.js", "export const x = '-ok';\n")
        file(dir, "lib/b.js", "export default { label: 'ESM' };\n")

        val run = JsPluginRuntime().execute(dir, "npm.esm-pack").getOrThrow()

        val c = run.contributions.single()
        assertEquals("esm", c.id)
        assertEquals("ESM-ok", c.label)
        assertEquals("ESM", c.subtitle)
    }

    @Test
    fun moduleIsEvaluatedOnlyOnce() {
        val dir = tempDir("esm-once")
        file(dir, "package.json", """{"type":"module","main":"index.js"}""")
        file(
            dir, "index.js",
            """
            import { hit } from './counter.js';
            import { hit as again } from './counter.js';
            ctx.log("count=" + hit + "/" + again);
            """.trimIndent(),
        )
        file(dir, "counter.js", "ctx.log('counter 执行一次');\nexport const hit = 'h';\n")

        val run = JsPluginRuntime().execute(dir, "npm.once").getOrThrow()
        // 只有第一次 __require 真正执行文件
        assertEquals(1, run.logs.count { it == "counter 执行一次" })
        assertTrue(run.logs.contains("count=h/h"))
    }

    @Test
    fun bareModuleFailsWithStageAndModuleName() {
        val dir = tempDir("esm-bare")
        file(dir, "package.json", """{"type":"module","main":"index.js"}""")
        file(dir, "index.js", "import React from 'react';\nctx.log('不会跑到这里');\n")

        val result = JsPluginRuntime().execute(dir, "npm.bare")
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as JsPluginError
        assertEquals(err.message, JsPluginRuntime.STAGE_LOAD, err.stage)
        assertTrue(err.message, err.message.contains("\"react\""))
        assertTrue(err.message, err.message.contains("不安装 npm 依赖"))
    }

    @Test
    fun pathEscapeIsRejected() {
        val dir = tempDir("esm-escape")
        file(dir, "package.json", """{"type":"module","main":"index.js"}""")
        file(dir, "index.js", "import { x } from '../../etc/passwd';\n")

        val result = JsPluginRuntime().execute(dir, "npm.escape")
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as JsPluginError
        assertEquals(err.message, JsPluginRuntime.STAGE_LOAD, err.stage)
        assertTrue(err.message, err.message.contains("越界"))
    }

    @Test
    fun unsupportedExportStarFailsAtCompileStage() {
        val dir = tempDir("esm-star")
        file(dir, "package.json", """{"type":"module","main":"index.js"}""")
        file(dir, "index.js", "export * from './lib/a.js';\n")
        file(dir, "lib/a.js", "export const x = 1;\n")

        val err = JsPluginRuntime().execute(dir, "npm.star").exceptionOrNull() as JsPluginError
        assertEquals(JsPluginRuntime.STAGE_COMPILE, err.stage)
        assertTrue(err.message, err.message.contains("export *"))
        assertTrue(err.message, err.message.contains("index.js:1"))
    }

    @Test
    fun htmlBlockRenderersKeepsSetRenderBinaryAlias() {
        val cls = javaClass.classLoader!!.loadClass("dev.dsh.mobile.pluginapi.HtmlBlockRenderers")
        val method = cls.methods.firstOrNull { it.name == "setRender" }
        assertNotNull("HtmlBlockRenderers.setRender 是已发布插件 APK 的二进制兼容别名，不能被删", method)
        assertTrue("setRender 至少要收一个 block 参数", method!!.parameterTypes.isNotEmpty())
        assertEquals("setRender 的返回值必须是 Unit/Void", Void.TYPE, method.returnType)
    }
}
