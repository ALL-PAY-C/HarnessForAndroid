package dev.dsh.mobile.feature.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsToolsTest {
    @Test fun downloadLimitConvertsMiBWithoutOverflow() {
        assertEquals(64L * 1024 * 1024, downloadLimitBytes("64"))
        assertEquals(4096L * 1024 * 1024, downloadLimitBytes("4096"))
    }
    @Test fun invalidDownloadLimitsAreRejected() {
        listOf("0", "-1", "4097", "abc", "999999999999999999999").forEach {
            assertNull(downloadLimitBytes(it))
        }
    }
    @Test fun installationLabelRequiresVersionAndAbi() {
        assertNull(linuxInstallationLabel(null, null))
        assertEquals("3.21 / arm64-v8a", linuxInstallationLabel("3.21", "arm64-v8a"))
    }
    @Test fun partialInstallationDoesNotClaimInstalled() {
        assertNull(linuxInstallationLabel("3.21", ""))
        assertNull(linuxInstallationLabel("", "arm64-v8a"))
    }
    @Test fun downloadInputTrimsWhitespace() {
        assertEquals(1024L * 1024, downloadLimitBytes(" 1 "))
    }
    @Test fun blankProxyMeansDirect() {
        assertFalse(proxyInvalid(""))
        assertFalse(proxyInvalid("   "))
        assertNull(proxyLabel(""))
    }
    @Test fun validProxyUrlsAreAccepted() {
        listOf("http://127.0.0.1:7890", "https://proxy.local:8443", "socks5://10.0.0.2:1080", "127.0.0.1:7890").forEach {
            assertFalse("应接受：" + it, proxyInvalid(it))
        }
        assertEquals("http://127.0.0.1:7890", proxyLabel(" http://127.0.0.1:7890 "))
    }
    @Test fun brokenProxyUrlsAreRejected() {
        listOf("http://", "http://bad host:1", "ftp://127.0.0.1:21", "//").forEach {
            assertTrue("应拒绝：" + it, proxyInvalid(it))
        }
    }
}
