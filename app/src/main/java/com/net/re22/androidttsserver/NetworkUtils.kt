package net.re22.androidttsserver

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun getBestLocalIpAddress(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        for (networkInterface in interfaces.toList()) {
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            for (address in networkInterface.inetAddresses.toList()) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress ?: "127.0.0.1"
                }
            }
        }
        return "127.0.0.1"
    }
}
