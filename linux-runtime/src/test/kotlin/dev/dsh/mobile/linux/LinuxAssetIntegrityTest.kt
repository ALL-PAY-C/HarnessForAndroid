package dev.dsh.mobile.linux

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class LinuxAssetIntegrityTest {
    private fun root(): File = if (File("src/main/assets/linux-artifacts.json").isFile) File("..") else File(".")

    @Test fun `all bundled digest fields are complete and native files match`() {
        val root = root()
        val json = File(root, "linux-runtime/src/main/assets/linux-artifacts.json").readText()
        val manifests = LinuxArtifacts.parse(json)
        assertEquals(listOf("x86_64", "arm64-v8a"), manifests.map { it.abi })
        assertEquals(2, LinuxArtifacts.parse("  { \"nested\": " + json + " }  ").size)
        assertTrue(json.contains("1baab6133116d09ec2963b8a75838ed191bed29eb0986ce1d76a6caa2a10115f"))
        val digests = Regex("\"(?:sha256|loaderSha256|nodeSha256)\"\\s*:\\s*\"([^\"]*)\"").findAll(json).toList()
        assertTrue(digests.isNotEmpty())
        digests.forEach { assertTrue(it.value, it.groupValues[1].matches(Regex("[0-9a-f]{64}"))) }
        Regex("\\{[^{}]*\\}").findAll(json).forEach { match ->
            val body = match.value
            val abi = Regex("\"abi\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return@forEach
            if (!body.contains("loaderSha256")) return@forEach
            for ((lib, hashKey, sizeKey) in listOf(Triple("libproot.so", "sha256", "bytes"), Triple("libproot-loader.so", "loaderSha256", "loaderBytes"))) {
                val file = File(root, ".mock/agent-env/artifacts/$abi/$lib")
                if (!file.isFile) continue
                val expected = Regex("\"$hashKey\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
                val size = Regex("\"$sizeKey\"\\s*:\\s*(\\d+)").find(body)!!.groupValues[1].toLong()
                assertEquals(file.path, size, file.length())
                assertEquals(file.path, expected, MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
            }
        }
    }

    @Test fun `kernel archive includes direct profile boot imports`() {
        val assets = File(root(), "linux-runtime/src/main/assets/dsh-kernel")
        val manifest = checkNotNull(dev.dsh.mobile.linux.kernel.DshKernelArtifacts.parse(File(assets, "kernel-manifest.json").readText()))
        val selected = checkNotNull(dev.dsh.mobile.linux.kernel.DshKernelArtifacts.kernelArchive(manifest, LinuxAbis.X86_64))
        val archive = File(assets, selected.archive)
        val tar = java.util.zip.GZIPInputStream(archive.inputStream()).use { it.readBytes() }
        val entries = mutableMapOf<String, String>()
        var offset = 0
        while (offset + 512 <= tar.size && tar[offset] != 0.toByte()) {
            val shortName = String(tar, offset, 100).substringBefore('\u0000')
            val prefix = String(tar, offset + 345, 155).substringBefore('\u0000')
            val name = if (prefix.isEmpty()) shortName else "$prefix/$shortName"
            val size = String(tar, offset + 124, 12).trim('\u0000', ' ').toInt(8)
            entries[name] = String(tar, offset + 512, size)
            offset += 512 + ((size + 511) / 512) * 512
        }
        val boot = entries.filterKeys { it.contains("/dsh/lib/profile-boot-") && it.endsWith(".js") }.values.single()
        val imports = Regex("from \"(@deepseek-ai/[^\"]+)\"").findAll(boot).map { it.groupValues[1].split('/').take(2).joinToString("/") }.toSet()
        assertTrue(imports.contains("@deepseek-ai/dsh-http-proxy"))
        assertTrue(entries.containsKey("node_modules/node-addon-require-builtin-linux-x64-musl/prebuilt/linux-x64-musl-napi-v9.node"))
        assertTrue(entries.containsKey("profiles/headless/cordis.patch.yml"))
        assertFalse(entries.containsKey(".anonymous-user-id"))
        imports.forEach { assertTrue("missing profile boot dependency: $it", entries.containsKey("node_modules/$it/package.json")) }
    }

    @Test fun `object regex escapes both braces for Android ICU`() {
        val field = LinuxArtifacts::class.java.getDeclaredField("objectRe")
        field.isAccessible = true
        assertEquals("\\{[^{}]*\\}", (field.get(LinuxArtifacts) as Regex).pattern)
    }
}
