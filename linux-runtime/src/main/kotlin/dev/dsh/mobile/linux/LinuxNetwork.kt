package dev.dsh.mobile.linux

import java.io.File

/**
 * T5：guest 的网络配置文件（DNS 与 hosts）。
 *
 * 只往 rootfs 里写，不动宿主。/etc/hosts 只留回环：之前为了绕过 DNS 故障把
 * dl-cdn.alpinelinux.org 写死成公网 IP，那会掩盖问题，现在不写了。
 *
 * nameserver 的取值来自 [LinuxDns] 的实测结果，而不是硬编码：模拟器里
 * 8.8.8.8 / 1.1.1.1 不可达，而 musl 只要 resolv.conf 里有一个不可达的
 * nameserver 排在可达的后面，getaddrinfo 就整体返回 EAI_AGAIN（设备实测矩阵，
 * 见 .mock/agent-env/t5/REPORT.md 的 DNS 根因）。
 */
data class LinuxNetwork(val dnsServers: List<String>, val degraded: Boolean = false) {

    fun resolvConf(): String = buildString {
        if (degraded) append("# degraded: 未探测到应答，已写入备用 DNS\n")
        dnsServers.forEach { append("nameserver ").append(it).append('\n') }
    }

    fun hostsFile(): String = "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"

    /**
     * 幂等落盘：内容一致就不写。rootfs 不存在时直接返回 false，不凭空造目录。
     * @return 是否有文件被改写
     */
    fun applyTo(rootfs: File): Boolean {
        if (!rootfs.isDirectory) return false
        val etc = File(rootfs, "etc")
        var changed = writeIfChanged(File(etc, "resolv.conf"), resolvConf())
        if (writeIfChanged(File(etc, "hosts"), hostsFile())) changed = true
        return changed
    }

    companion object {
        const val RESOLV_CONF = "etc/resolv.conf"
        const val HOSTS = "etc/hosts"

        /** 兼容原入口；探测全失败时明确降级，避免产生空 resolv.conf。 */
        fun verified(dns: LinuxDns = LinuxDns): LinuxNetwork = fromAnswers(dns.resolveCached(), emptyList())

        fun verified(context: android.content.Context): LinuxNetwork = verified(
            systemDns = { AndroidSystemDns.servers(context) },
        )

        /** 系统 DNS 优先；注入来源与探测可在 JVM 上独立验证。 */
        fun verified(
            systemDns: () -> List<String>,
            props: List<String> = LinuxDns.deviceProps(),
            probe: (String) -> Boolean = { LinuxDns.answers(it) },
        ): LinuxNetwork {
            val system = LinuxDns.ipv4Servers(systemDns())
            return fromAnswers(LinuxDns.resolve(system + props, probe = probe), system)
        }

        private fun fromAnswers(answers: List<String>, system: List<String>): LinuxNetwork =
            if (answers.isNotEmpty()) LinuxNetwork(answers)
            else LinuxNetwork((system.take(1) + LinuxDns.DEGRADED).distinct().take(2), degraded = true)
    }
}

/** 内容变了才落盘（幂等）。 */
internal fun writeIfChanged(file: File, content: String): Boolean {
    if (file.isFile && file.readText() == content) return false
    file.parentFile?.mkdirs()
    file.writeText(content)
    return true
}
