package dev.dsh.mobile.linux

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxArm64NetworkTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun systemDnsPrecedesPropertiesAndFallback() {
        val probed = mutableListOf<String>()
        val network = LinuxNetwork.verified(
            systemDns = { listOf("10.169.62.32", "223.5.5.5") },
            props = listOf("114.114.114.114"),
            probe = { probed += it; true },
        )
        assertEquals(listOf("10.169.62.32", "223.5.5.5"), network.dnsServers)
        assertEquals(listOf("10.169.62.32", "223.5.5.5", "114.114.114.114", "10.0.2.3", "8.8.8.8", "1.1.1.1"), probed)
        assertFalse(network.degraded)
    }

    @Test fun unreachableSystemDnsIsDiscarded() {
        val network = LinuxNetwork.verified(
            systemDns = { listOf("10.20.0.2", "10.169.62.32") }, props = emptyList(),
            probe = { it == "10.169.62.32" || it == "223.5.5.5" },
        )
        assertEquals(listOf("10.169.62.32", "223.5.5.5"), network.dnsServers)
        assertFalse(network.degraded)
    }

    @Test fun allUnreachableWritesExplicitDegradedResolvers() {
        val network = LinuxNetwork.verified(systemDns = { emptyList() }, props = emptyList(), probe = { false })
        assertTrue(network.degraded)
        assertEquals(listOf("223.5.5.5", "114.114.114.114"), network.dnsServers)
        val root = tmp.newFolder()
        assertTrue(network.applyTo(root))
        val text = File(root, LinuxNetwork.RESOLV_CONF).readText()
        assertTrue(text.contains("degraded"))
        assertTrue(text.contains("nameserver 223.5.5.5\n"))
        assertTrue(text.contains("nameserver 114.114.114.114\n"))
        assertFalse(network.applyTo(root))
    }

    @Test fun unprobedSystemDnsIsFirstDegradedResolver() {
        val network = LinuxNetwork.verified(systemDns = { listOf("10.20.0.2") }, props = emptyList(), probe = { false })
        assertTrue(network.degraded)
        assertEquals(listOf("10.20.0.2", "223.5.5.5"), network.dnsServers)
    }

    @Test fun defaultProbeIsBusinessARecord() {
        assertEquals("example.com", LinuxDns.PROBE_NAME)
        assertEquals(1, LinuxDns.PROBE_QTYPE)
        assertEquals(29, LinuxDns.query().size)
    }

    @Test fun systemCandidatesExcludeIpv6LoopbackAndDuplicates() {
        assertEquals(listOf("10.169.62.32", "223.5.5.5"), LinuxDns.ipv4Servers(listOf(
            "::1", "2001:db8::1", "127.0.0.1", "10.169.62.32", "223.5.5.5", "10.169.62.32",
        )))
    }
}
