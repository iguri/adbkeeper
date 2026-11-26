package com.adbkeeper.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address

/** 读取手机当前局域网地址，方便拼出 adb connect 命令。不需要任何运行时权限。 */
object NetInfo {

    fun wifiIpv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = mutableListOf<Pair<Int, String>>()
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val rank = transportRank(caps)
            val link = cm.getLinkProperties(network) ?: continue
            val address = link.linkAddresses
                .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.address ?: continue
            val host = address.hostAddress ?: continue
            candidates += rank to host
        }
        return candidates.minByOrNull { it.first }?.second
    }

    /** 全局 IPv6（跳过 fe80 链路本地和回环），没有则返回 null。 */
    fun wifiIpv6(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = mutableListOf<Pair<Int, String>>()
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val rank = transportRank(caps)
            val link = cm.getLinkProperties(network) ?: continue
            for (linkAddress in link.linkAddresses) {
                val address = linkAddress.address
                if (address !is Inet6Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress) continue
                val host = address.hostAddress?.substringBefore('%') ?: continue
                // 优先全局地址，其次唯一本地地址（fc00::/7）
                val isUla = host.startsWith("fc", ignoreCase = true) || host.startsWith("fd", ignoreCase = true)
                candidates += (rank * 2 + if (isUla) 1 else 0) to host
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }

    private fun transportRank(caps: NetworkCapabilities): Int = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
        else -> 2
    }

    /** 当前所有网络接口上的地址（去掉 scope 后缀），用于过滤 mDNS 发现的“其它手机”。 */
    fun allLocalAddresses(context: Context): Set<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
        val result = mutableSetOf<String>()
        for (network in cm.allNetworks) {
            val link = cm.getLinkProperties(network) ?: continue
            for (linkAddress in link.linkAddresses) {
                linkAddress.address.hostAddress?.substringBefore('%')?.let { result += it }
            }
        }
        return result
    }
}
