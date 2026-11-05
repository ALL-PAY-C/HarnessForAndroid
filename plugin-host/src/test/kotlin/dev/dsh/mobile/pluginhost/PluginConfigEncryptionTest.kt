package dev.dsh.mobile.pluginhost

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest

/**
 * B2：插件配置落盘加密。
 *
 * 假 SharedPreferences 会真的写一个文件（`dsh.plugin.packages.xml`），断言直接 grep 文件内容 ——
 * 内存里看不见明文、磁盘上仍是明文的情况必须能被抓出来。
 */
class PluginConfigEncryptionTest {

    private class Fixture(val dir: File) {
        val prefsFile: File = File(dir, "dsh.plugin.packages.xml")
        val values: MutableMap<String, String> = mutableMapOf()
        val prefs: SharedPreferences = fileBackedPrefs(prefsFile, values)
        val vault: PluginConfigVault = PluginConfigVault(File(dir, "plugin-config.key"))
        val installed: InstalledPlugins = InstalledPlugins(prefs, vault)

        /** 模拟旧版本直接写进 prefs 的明文（绕过 setConfig）。 */
        fun putLegacy(id: String, json: String) {
            values["values." + id] = json
            flush()
        }

        fun flush() {
            prefsFile.writeText(values.entries.joinToString("\n") { it.key + "=" + it.value })
        }

        fun close() {
            dir.deleteRecursively()
        }
    }

    private fun fixture(): Fixture = Fixture(Files.createTempDirectory("plugin-config").toFile())

    @Test
    fun secretValueNeverHitsDiskInPlaintext() {
        val f = fixture()
        try {
            evidence("before-write", f)
            f.installed.setConfig("dsh.demo", "apiKey", "sk-live-TOPSECRET-42")
            f.installed.setConfig("dsh.demo", "gateway", "https://gateway.test")

            evidence("after-write", f)
            val raw = f.values.getValue("values.dsh.demo")
            val disk = f.prefsFile.readText()
            assertTrue(raw, raw.startsWith(PluginConfigVault.PREFIX))
            assertFalse(disk, disk.contains("sk-live-TOPSECRET-42"))
            // 整段配置 JSON 都被密文覆盖：键名也不该留在文件里。
            assertFalse(disk, disk.contains("apiKey"))
            assertFalse(disk, disk.contains("gateway.test"))

            assertEquals("sk-live-TOPSECRET-42", f.installed.config("dsh.demo")["apiKey"])
            assertEquals("https://gateway.test", f.installed.config("dsh.demo")["gateway"])
        } finally {
            f.close()
        }
    }

    @Test
    fun legacyPlaintextMigratesOnFirstReadAndKeepsValues() {
        val f = fixture()
        try {
            val legacy = """{"apiKey":"legacy-SECRET-7","gateway":"https://gateway.test"}"""
            f.putLegacy("dsh.demo", legacy)
            assertTrue(f.prefsFile.readText().contains("legacy-SECRET-7"))
            evidence("legacy-plaintext", f)

            val read = f.installed.config("dsh.demo")
            evidence("after-migration", f)

            assertEquals(mapOf("apiKey" to "legacy-SECRET-7", "gateway" to "https://gateway.test"), read)
            assertTrue(f.values.getValue("values.dsh.demo").startsWith(PluginConfigVault.PREFIX))
            assertFalse(f.prefsFile.readText().contains("legacy-SECRET-7"))
        } finally {
            f.close()
        }
    }

    @Test
    fun migrationIsIdempotent() {
        val f = fixture()
        try {
            f.putLegacy("dsh.demo", """{"token":"legacy-SECRET-9"}""")
            val first = f.installed.config("dsh.demo")
            val sealed = f.values.getValue("values.dsh.demo")

            repeat(3) { assertEquals(first, f.installed.config("dsh.demo")) }

            // 同一个键被覆盖，不是又写了一份：读多少次都只有一条 values.*。
            assertEquals(sealed, f.values.getValue("values.dsh.demo"))
            assertEquals(1, f.values.keys.count { it.startsWith("values.") })
        } finally {
            f.close()
        }
    }

    @Test
    fun lostKeyYieldsEmptyConfigInsteadOfCrash() {
        val dir = Files.createTempDirectory("plugin-config-lost").toFile()
        try {
            val values = mutableMapOf<String, String>()
            val file = File(dir, "dsh.plugin.packages.xml")
            val prefs = fileBackedPrefs(file, values)
            InstalledPlugins(prefs, PluginConfigVault(File(dir, "a.key")))
                .setConfig("dsh.demo", "apiKey", "sk-live-TOPSECRET-42")

            // 换机 / 清数据：密钥文件没了，旧密文解不开 → 按「没有配置」处理。
            val reopened = InstalledPlugins(prefs, PluginConfigVault(File(dir, "b.key")))
            assertEquals(emptyMap<String, String>(), reopened.config("dsh.demo"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun repeatedWritesAndReadsDoNotRederive() {
        val f = fixture()
        try {
            f.installed.setConfig("dsh.demo", "apiKey", "v0")
            val opens = f.vault.openCount
            val seals = f.vault.sealCount

            repeat(20) { i ->
                f.installed.setConfig("dsh.demo", "k" + i, "v" + i)
                assertEquals("v" + i, f.installed.config("dsh.demo")["k" + i])
            }
            repeat(20) { f.installed.config("dsh.demo") }

            println("[B2] hot-path opens=" + (f.vault.openCount - opens) + " seals=" + (f.vault.sealCount - seals) + " keyLoads=" + f.vault.keyLoads)
            // 读路径一次都没再解密（PBKDF2-120k 不再落在 UI 回调里）。
            assertTrue("opens=" + f.vault.openCount, f.vault.openCount - opens <= 1)
            // 写路径仍是每次一个 seal：新 salt/iv 是密文的一部分，这条不能省。
            assertEquals(20, f.vault.sealCount - seals)
            // 密钥只从盘上读过一次，之后走内存。
            assertEquals(1, f.vault.keyLoads)
        } finally {
            f.close()
        }
    }

    @Test
    fun cacheInvalidatesWhenPrefsChangeExternally() {
        val f = fixture()
        try {
            f.installed.setConfig("dsh.demo", "apiKey", "old-SECRET-1")
            assertEquals("old-SECRET-1", f.installed.config("dsh.demo")["apiKey"])

            // 备份恢复：别的写入者绕过本实例改了 prefs（旧版明文口径），缓存必须失效。
            f.putLegacy("dsh.demo", "{\"apiKey\":\"restored-SECRET-2\"}")

            assertEquals("restored-SECRET-2", f.installed.config("dsh.demo")["apiKey"])
            assertTrue(f.values.getValue("values.dsh.demo").startsWith(PluginConfigVault.PREFIX))
        } finally {
            f.close()
        }
    }

    @Test
    fun removeClearsCachedConfig() {
        val f = fixture()
        try {
            f.installed.setConfig("dsh.demo", "apiKey", "sk-remove-me")
            assertEquals("sk-remove-me", f.installed.config("dsh.demo")["apiKey"])

            f.installed.remove("dsh.demo")
            assertEquals(emptyMap<String, String>(), f.installed.config("dsh.demo"))

            // 删掉后重写不会把缓存里的旧值带回来。
            f.installed.setConfig("dsh.demo", "apiKey", "sk-fresh")
            assertEquals(mapOf("apiKey" to "sk-fresh"), f.installed.config("dsh.demo"))
        } finally {
            f.close()
        }
    }

    @Test
    fun hotPathStillWritesCiphertext() {
        val f = fixture()
        try {
            repeat(20) { i -> f.installed.setConfig("dsh.demo", "k" + i, "value-" + i) }
            evidence("after-20-writes", f)

            val disk = f.prefsFile.readText()
            assertTrue(f.values.getValue("values.dsh.demo").startsWith(PluginConfigVault.PREFIX))
            assertFalse(disk, disk.contains("value-0"))
            assertFalse(disk, disk.contains("value-19"))
            assertEquals("value-19", f.installed.config("dsh.demo")["k19"])
        } finally {
            f.close()
        }
    }

    private companion object {
        /** 把磁盘上的 prefs 文件做成可比对的证据：名字 / 字节数 / sha256 前 16 位 / 前 120 字符预览。 */
        fun evidence(tag: String, f: Fixture) {
            val bytes = if (f.prefsFile.exists()) f.prefsFile.readBytes() else ByteArray(0)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            println("[B2] " + tag + " file=" + f.prefsFile.name + " bytes=" + bytes.size +
                " sha256=" + sha.take(16) + " preview=" + bytes.toString(Charsets.UTF_8).take(120))
        }

        fun fileBackedPrefs(file: File, values: MutableMap<String, String>): SharedPreferences {
            val pending = mutableMapOf<String, String?>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putString" -> {
                        pending[args!![0] as String] = args[1] as String?
                        editor
                    }
                    "remove" -> {
                        pending[args!![0] as String] = null
                        editor
                    }
                    "apply", "commit" -> {
                        pending.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        pending.clear()
                        file.parentFile?.mkdirs()
                        file.writeText(values.entries.joinToString("\n") { it.key + "=" + it.value })
                        if (method.name == "commit") true else null
                    }
                    else -> error("unexpected editor call: " + method.name)
                }
            } as SharedPreferences.Editor
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getString" -> values[args!![0] as String] ?: args[1]
                    "contains" -> values.containsKey(args!![0] as String)
                    "edit" -> editor
                    else -> error("unexpected preferences call: " + method.name)
                }
            } as SharedPreferences
        }
    }
}
