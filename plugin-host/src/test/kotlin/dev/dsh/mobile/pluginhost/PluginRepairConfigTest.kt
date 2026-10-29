package dev.dsh.mobile.pluginhost

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class PluginRepairConfigTest {
    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, String>()
        val pending = mutableMapOf<String, String?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> { pending[args!![0] as String] = args[1] as String?; editor }
                "remove" -> { pending[args!![0] as String] = null; editor }
                "apply", "commit" -> {
                    pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    pending.clear()
                    if (method.name == "commit") true else null
                }
                else -> error("unexpected editor call: " + method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("unexpected preferences call: " + method.name)
            }
        } as SharedPreferences
    }

    @Test
    fun repairReinstallsPayloadAndPreservesUserConfiguration() = runBlocking {
        val prefs = preferences()
        val installed = InstalledPlugins(object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        })
        val root = Files.createTempDirectory("repair-config").toFile()
        val source = Files.createTempDirectory("repair-source").toFile()
        try {
            File(source, "plugin.json").writeText("""{"id":"dsh.demo","name":"Demo","version":"1.0.0"}""")
            File(source, "payload.txt").writeText("original")
            val service = PluginPackageService(root, installed.registry())
            assertTrue(service.installFromDirectory(source).ok)
            installed.setConfig("dsh.demo", "gateway", "https://gateway.test/custom")
            installed.setConfig("dsh.demo", "token", "user-value")
            File(service.activeDir("dsh.demo")!!, "payload.txt").writeText("damaged")
            File(source, "payload.txt").writeText("repaired")

            val repaired = service.repair("dsh.demo", source)

            assertTrue(repaired.error, repaired.ok)
            assertEquals("repaired", File(service.activeDir("dsh.demo")!!, "payload.txt").readText())
            assertEquals(mapOf("gateway" to "https://gateway.test/custom", "token" to "user-value"), installed.config("dsh.demo"))
            assertEquals("1.0.0", installed.get("dsh.demo")!!.version)
        } finally {
            root.deleteRecursively()
            source.deleteRecursively()
        }
    }
}
