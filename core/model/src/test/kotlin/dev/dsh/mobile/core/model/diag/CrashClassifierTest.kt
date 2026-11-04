package dev.dsh.mobile.core.model.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashClassifierTest {

    private fun classify(
        thread: String = "main",
        type: String,
        message: String? = null,
        stack: List<String> = listOf("dev.dsh.mobile.app.AppContainer.connect(AppContainer.kt:708)"),
    ) = CrashClassifier.classify(thread, type, message, stack)

    @Test
    fun outOfMemoryIsOom() {
        val f = classify(type = "java.lang.OutOfMemoryError", message = "Failed to allocate a 32MB allocation")
        assertEquals(CrashKind.OOM, f.kind)
    }

    @Test
    fun pluginFrameIsPlugin() {
        val f = classify(
            type = "java.lang.IllegalStateException",
            stack = listOf("dev.dsh.mobile.pluginhost.PluginHost.ensureStarted(PluginHost.kt:278)"),
        )
        assertEquals(CrashKind.PLUGIN, f.kind)
    }

    @Test
    fun engineFrameIsEngine() {
        val f = classify(
            type = "java.lang.RuntimeException",
            stack = listOf("dev.dsh.mobile.app.node.RemoteNodeEngine.lost(RemoteNodeEngine.kt:250)"),
        )
        assertEquals(CrashKind.ENGINE, f.kind)
    }

    @Test
    fun sqliteTypeIsDatabase() {
        val f = classify(type = "android.database.sqlite.SQLiteException", message = "no such table: session")
        assertEquals(CrashKind.DATABASE, f.kind)
    }

    @Test
    fun mainThreadWithoutMarkersIsUi() {
        val f = classify(thread = "main", type = "java.lang.NullPointerException")
        assertEquals(CrashKind.UI, f.kind)
    }

    @Test
    fun backgroundThreadWithoutMarkersIsUnknown() {
        val f = classify(thread = "RenderThread", type = "java.lang.NullPointerException")
        assertEquals(CrashKind.UNKNOWN, f.kind)
    }

    @Test
    fun oomWinsOverPluginFrames() {
        val f = classify(
            type = "java.lang.OutOfMemoryError",
            stack = listOf("dev.dsh.mobile.pluginhost.PluginHost.register(PluginHost.kt:159)"),
        )
        assertEquals(CrashKind.OOM, f.kind)
    }

    @Test
    fun sameCrashWithDifferentLineNumbersSharesKey() {
        val a = classify(
            type = "java.lang.IllegalStateException",
            stack = listOf(
                "java.lang.Thread.run(Thread.java:1012)",
                "dev.dsh.mobile.app.AppContainer.connect(AppContainer.kt:708)",
            ),
        )
        val b = classify(
            type = "java.lang.IllegalStateException",
            stack = listOf(
                "java.lang.Thread.run(Thread.java:1012)",
                "dev.dsh.mobile.app.AppContainer.connect(AppContainer.kt:812)",
            ),
        )
        assertEquals(a.key, b.key)
        assertTrue(a.key.contains("AppContainer.connect"))
        assertFalse(a.key.contains("708"))
    }

    @Test
    fun differentTypesProduceDifferentKeys() {
        val a = classify(type = "java.lang.IllegalStateException")
        val b = classify(type = "java.lang.IllegalArgumentException")
        assertFalse(a.key == b.key)
    }

    @Test
    fun keySkipsFrameworkFrames() {
        val f = classify(
            type = "java.lang.RuntimeException",
            stack = listOf(
                "java.lang.reflect.Method.invoke(Method.java:566)",
                "kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:106)",
                "dev.dsh.mobile.app.AppNav.render(AppNav.kt:159)",
            ),
        )
        assertTrue(f.key.endsWith("dev.dsh.mobile.app.AppNav.render(AppNav.kt)"))
    }

    @Test
    fun framesAreBoundedToSixteen() {
        val stack = (1..40).map { "dev.dsh.mobile.app.Foo.bar(Foo.kt:$it)" }
        val f = classify(type = "java.lang.RuntimeException", stack = stack)
        assertEquals(16, f.frames.size)
    }
}
