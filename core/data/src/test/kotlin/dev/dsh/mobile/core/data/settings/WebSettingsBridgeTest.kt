package dev.dsh.mobile.core.data.settings

import dev.dsh.mobile.core.data.secret.EndpointSecretStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WebSettingsBridgeTest {

    private lateinit var dir: File
    private lateinit var secrets: EndpointSecretStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("dsh-web-settings").toFile()
        secrets = EndpointSecretStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun keylessMachineKeepsEmptyKey() {
        val web = AppSettings().toWebSettings(secrets)
        assertEquals("auto", web.engine)
        assertEquals("", web.exaApiKey)
        assertEquals("", web.searxngInstance)
    }

    @Test
    fun thoughtsExpandedDefaultsToTrue() {
        assertTrue("思考过程应默认展开", AppSettings().thoughtsExpanded)
    }

    @Test
    fun storedKeyAndInstanceArePickedUp() {
        secrets.set(WebSecretKeys.EXA, "exa-key-123")
        val web = AppSettings(
            webSearchEngine = "exa",
            searxngInstance = "https://searx.example",
            maxDownloadBytes = 1024L,
        ).toWebSettings(secrets)
        assertEquals("exa", web.engine)
        assertEquals("exa-key-123", web.exaApiKey)
        assertEquals("https://searx.example", web.searxngInstance)
        assertEquals(1024L, web.maxDownloadBytes)
        assertTrue(File(dir, "endpoint-tokens.json").exists())
    }
}
