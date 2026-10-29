package dev.dsh.mobile.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EsmTransform 的逐条契约：每种 ESM 写法都落到一条确定的降级结果上。
 *
 * 重点是「认不出的写法必须炸」—— 静默错改会让插件在别处以莫名其妙的
 * ReferenceError 挂掉，比直接报错难查得多。
 */
class EsmTransformTest {

    private fun t(src: String) = EsmTransform.transform(src, "demo.js").trim()

    @Test
    fun defaultImport() {
        assertEquals("const React = __interop(__require('react'));", t("import React from 'react';"))
    }

    @Test
    fun namespaceImport() {
        assertEquals("const ns = __require('m');", t("import * as ns from 'm';"))
    }

    @Test
    fun namedImportWithAlias() {
        assertEquals("const { a, b: c } = __require('m');", t("import { a, b as c } from 'm';"))
    }

    @Test
    fun sideEffectImport() {
        assertEquals("__require('m');", t("import 'm';"))
    }

    @Test
    fun defaultAndNamedImport() {
        assertEquals(
            "const d = __interop(__require('m')); const { a } = __require('m');",
            t("import d, { a } from 'm';"),
        )
    }

    @Test
    fun defaultAndNamespaceImport() {
        assertEquals(
            "const ns = __require('m'); const d = __interop(ns);",
            t("import d, * as ns from 'm';"),
        )
    }

    @Test
    fun exportDefaultExpression() {
        assertEquals("__exports.default = 42;", t("export default 42;"))
    }

    @Test
    fun exportDefaultFunctionKeepsName() {
        val out = EsmTransform.transform("export default function foo() { return 1; }", "demo.js")
        assertTrue(out, out.contains("function foo() { return 1; }"))
        assertTrue(out, out.contains("__exports.default = foo;"))
        // 不能留下 export 关键字 —— Rhino 会直接编译失败
        assertTrue(out, !out.contains("export default"))
    }

    @Test
    fun exportDefaultAnonymousFunction() {
        val out = EsmTransform.transform("export default function () { return 1; }", "demo.js")
        assertTrue(out, out.contains("__exports.default = function () { return 1; }"))
    }

    @Test
    fun exportConstDeclaresAndBackfills() {
        val out = EsmTransform.transform("export const A = 1;", "demo.js")
        assertTrue(out, out.contains("const A = 1;"))
        assertTrue(out, out.contains("__exports.A = A;"))
    }

    @Test
    fun exportMultipleDeclarators() {
        val out = EsmTransform.transform("export const A = 1, B = fn(1, 2);", "demo.js")
        assertTrue(out, out.contains("const A = 1, B = fn(1, 2);"))
        assertTrue(out, out.contains("__exports.A = A;"))
        assertTrue(out, out.contains("__exports.B = B;"))
    }

    @Test
    fun exportFunctionAndClass() {
        val fn = EsmTransform.transform("export function hello() { return 1; }", "demo.js")
        assertTrue(fn, fn.contains("function hello() { return 1; }"))
        assertTrue(fn, fn.contains("__exports.hello = hello;"))

        val cls = EsmTransform.transform("export class Box { }", "demo.js")
        assertTrue(cls, cls.contains("class Box { }"))
        assertTrue(cls, cls.contains("__exports.Box = Box;"))
    }

    @Test
    fun exportNamedListWithAlias() {
        val out = EsmTransform.transform("export { a, b as c };", "demo.js")
        assertTrue(out, out.contains("__exports.a = a;"))
        assertTrue(out, out.contains("__exports.c = b;"))
    }

    @Test
    fun importMetaUrlBecomesEmptyString() {
        assertEquals("const u = '';", t("const u = import.meta.url;"))
    }

    @Test
    fun plainScriptIsReturnedByteIdentical() {
        val src = "ctx.log(\"hi\");\nvar a = 1;\nfunction f() { return a; }\n"
        assertEquals(src, EsmTransform.transform(src, "demo.js"))
    }

    @Test
    fun transformIsIdempotent() {
        val once = EsmTransform.transform(
            "import { a } from './lib/a.js';\nexport default function f() { return a; }\n",
            "demo.js",
        )
        assertEquals(once, EsmTransform.transform(once, "demo.js"))
    }

    @Test
    fun exportStarFailsLoudly() {
        val e = runCatching { EsmTransform.transform("export * from './m';", "demo.js") }.exceptionOrNull()
        assertTrue(e is EsmTransform.UnsupportedException)
        val u = e as EsmTransform.UnsupportedException
        assertEquals("demo.js", u.file)
        assertEquals(1, u.line)
        assertTrue(u.message!!.contains("export *"))
    }

    @Test
    fun reExportFromFailsLoudly() {
        val e = runCatching { EsmTransform.transform("export { x } from './m';", "demo.js") }.exceptionOrNull()
        assertTrue(e is EsmTransform.UnsupportedException)
        assertTrue(e!!.message!!.contains("再导出"))
    }

    @Test
    fun dynamicImportFailsLoudly() {
        val src = "var p;\nvar q = import('./m');\n"
        val e = runCatching { EsmTransform.transform(src, "demo.js") }.exceptionOrNull()
        assertTrue(e is EsmTransform.UnsupportedException)
        assertEquals(2, (e as EsmTransform.UnsupportedException).line)
        assertTrue(e.message!!.contains("动态 import()"))
    }

    @Test
    fun lineNumbersSurviveLeadingCommentBlock() {
        val src = "/*\nexport * from 'x';\n*/\nexport * from 'y';\n"
        val e = runCatching { EsmTransform.transform(src, "demo.js") }.exceptionOrNull()
        assertTrue(e is EsmTransform.UnsupportedException)
        assertEquals(4, (e as EsmTransform.UnsupportedException).line)
    }
}
