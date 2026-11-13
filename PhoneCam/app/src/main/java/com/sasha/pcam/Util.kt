package com.sasha.pcam

import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface

class DeviceInfo(
    val name: String,
    val uuid: String,
    @Volatile var w: Int,
    @Volatile var h: Int,
    @Volatile var fps: Int
)

object Util {
    fun localIp(): String {
        var fallback: String? = null
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        if (ni.name.startsWith("wlan")) return a.hostAddress ?: continue
                        if (fallback == null) fallback = a.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return fallback ?: "0.0.0.0"
    }

    fun readLine(inp: InputStream, first: Int = -1): String? {
        val sb = StringBuilder()
        var c = if (first >= 0) first else inp.read()
        if (c < 0) return null
        while (c >= 0 && c != '\n'.code) {
            if (c != '\r'.code) sb.append(c.toChar())
            c = inp.read()
        }
        return sb.toString()
    }

    /** Splits an Annex B byte stream into NAL units (without start codes). */
    fun splitNals(d: ByteArray): List<ByteArray> {
        val res = ArrayList<ByteArray>()
        var i = 0
        var start = -1
        while (i + 3 <= d.size) {
            val z0 = d[i] == 0.toByte()
            val z1 = d[i + 1] == 0.toByte()
            if (z0 && z1 && d[i + 2] == 1.toByte()) {
                if (start >= 0) res.add(d.copyOfRange(start, i))
                start = i + 3
                i += 3
            } else if (z0 && z1 && i + 3 < d.size && d[i + 2] == 0.toByte() && d[i + 3] == 1.toByte()) {
                if (start >= 0) res.add(d.copyOfRange(start, i))
                start = i + 4
                i += 4
            } else {
                i++
            }
        }
        if (start in 0 until d.size) res.add(d.copyOfRange(start, d.size))
        return res
    }

    fun scopes(info: DeviceInfo): String =
        "onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/Profile/Streaming " +
            "onvif://www.onvif.org/name/${info.name} onvif://www.onvif.org/hardware/PhoneCam " +
            "onvif://www.onvif.org/location/phone"
}
