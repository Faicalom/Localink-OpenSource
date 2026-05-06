package com.localbridge.android.core.network

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object LocalNetworkResolver {
    data class BroadcastRoute(
        val localAddress: Inet4Address,
        val broadcastAddress: Inet4Address
    )

    fun getBroadcastRoutes(): List<BroadcastRoute> {
        val lanFirst = rankedIpv4Interfaces()
        val preferred = lanFirst.filter { endpoint -> endpoint.isPreferredLan }
        val candidates = if (preferred.isNotEmpty()) preferred else lanFirst

        return candidates
            .mapNotNull { endpoint ->
                val broadcastAddress = endpoint.broadcastAddress ?: return@mapNotNull null
                BroadcastRoute(
                    localAddress = endpoint.address,
                    broadcastAddress = broadcastAddress
                )
            }
    }

    fun getLocalIpv4Addresses(): List<InetAddress> {
        return rankedIpv4Interfaces().map { endpoint -> endpoint.address }
    }

    fun firstLocalIpv4Address(): String {
        return getLocalIpv4Addresses().firstOrNull()?.hostAddress ?: "0.0.0.0"
    }

    fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        return runCatching {
            wifiManager.createMulticastLock("LocalBridgeDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun interfaces(): List<NetworkInterface> {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { networkInterface ->
                    runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)
                }
        }.getOrElse { emptyList() }
    }

    private fun rankedIpv4Interfaces(): List<RankedIpv4Endpoint> {
        return interfaces()
            .flatMap { networkInterface ->
                networkInterface.interfaceAddresses.mapNotNull { interfaceAddress ->
                    val address = interfaceAddress.address as? Inet4Address ?: return@mapNotNull null
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) {
                        return@mapNotNull null
                    }

                    RankedIpv4Endpoint(
                        address = address,
                        broadcastAddress = interfaceAddress.broadcast as? Inet4Address,
                        priority = scoreInterface(networkInterface, address),
                        isPreferredLan = isPreferredLanInterface(networkInterface, address)
                    )
                }
            }
            .sortedByDescending { endpoint -> endpoint.priority }
            .distinctBy { endpoint -> endpoint.address.hostAddress }
    }

    private fun scoreInterface(networkInterface: NetworkInterface, address: Inet4Address): Int {
        val adapterName = buildString {
            append(networkInterface.name.orEmpty())
            append(' ')
            append(networkInterface.displayName.orEmpty())
        }.lowercase()

        var score = if (address.isSiteLocalAddress) 100 else 10

        score += when {
            adapterName.contains("wlan") ||
                adapterName.contains("wifi") ||
                adapterName.contains("wi-fi") ||
                adapterName.contains("hotspot") ||
                adapterName.contains("ap") -> 60

            adapterName.contains("eth") ||
                adapterName.contains("ethernet") ||
                adapterName.contains("usb") ||
                adapterName.contains("rndis") -> 40

            adapterName.contains("pdanet") ||
                adapterName.contains("proxy") ||
                adapterName.contains("wireguard") ||
                adapterName.contains("zerotier") ||
                adapterName.contains("tailscale") ||
                adapterName.contains("openvpn") ||
                adapterName.contains("bridge") ||
                adapterName.contains("virtual") -> -10

            adapterName.contains("ppp") ||
                adapterName.contains("tun") ||
                adapterName.contains("tap") ||
                adapterName.contains("vpn") -> 8

            adapterName.contains("rmnet") ||
                adapterName.contains("ccmni") ||
                adapterName.contains("radio") ||
                adapterName.contains("cell") -> -40

            else -> 0
        }

        if (runCatching { networkInterface.isVirtual }.getOrDefault(false)) {
            score -= 5
        }

        return score
    }

    private fun isPreferredLanInterface(networkInterface: NetworkInterface, address: Inet4Address): Boolean {
        if (!address.isSiteLocalAddress) {
            return false
        }

        val adapterName = buildString {
            append(networkInterface.name.orEmpty())
            append(' ')
            append(networkInterface.displayName.orEmpty())
        }.lowercase()

        if (adapterName.contains("ppp") ||
            adapterName.contains("tun") ||
            adapterName.contains("tap") ||
            adapterName.contains("vpn") ||
            adapterName.contains("pdanet") ||
            adapterName.contains("proxy") ||
            adapterName.contains("wireguard") ||
            adapterName.contains("zerotier") ||
            adapterName.contains("tailscale") ||
            adapterName.contains("openvpn") ||
            adapterName.contains("virtual") ||
            adapterName.contains("bridge") ||
            adapterName.contains("rmnet") ||
            adapterName.contains("ccmni") ||
            adapterName.contains("radio") ||
            adapterName.contains("cell")) {
            return false
        }

        return true
    }

    private fun java.util.Enumeration<InetAddress>.toList(): List<InetAddress> {
        return Collections.list(this)
    }

    private data class RankedIpv4Endpoint(
        val address: Inet4Address,
        val broadcastAddress: Inet4Address?,
        val priority: Int,
        val isPreferredLan: Boolean
    )
}
