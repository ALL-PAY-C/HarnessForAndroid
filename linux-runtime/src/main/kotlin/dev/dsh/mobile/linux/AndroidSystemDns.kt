package dev.dsh.mobile.linux

import android.content.Context
import android.net.ConnectivityManager

/** 活跃网络（含 VPN）优先，随后补充 Wi-Fi/移动网络；网络切换时允许某项已消失。 */
object AndroidSystemDns {
    @Suppress("DEPRECATION")
    fun servers(context: Context): List<String> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        return runCatching {
            val networks = (listOfNotNull(manager.activeNetwork) + manager.allNetworks).distinct()
            LinuxDns.ipv4Servers(networks.flatMap { network ->
                runCatching { manager.getLinkProperties(network)?.dnsServers.orEmpty().mapNotNull { it.hostAddress } }
                    .getOrDefault(emptyList())
            })
        }.getOrDefault(emptyList())
    }
}
