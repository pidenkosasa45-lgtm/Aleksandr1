package com.sasha.pcam

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.UUID
import kotlin.concurrent.thread

class WsDiscovery(private val info: DeviceInfo, private val onvifPort: Int) {
    private var sock: MulticastSocket? = null
    @Volatile private var running = false
    private val group = InetAddress.getByName("239.255.255.250")

    fun start() {
        running = true
        val s = MulticastSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(3702))
        val ni = NetworkInterface.getByName("wlan0")
        try {
            if (ni != null) s.joinGroup(InetSocketAddress(group, 3702), ni) else s.joinGroup(group)
        } catch (_: Exception) {
            try { s.joinGroup(group) } catch (_: Exception) {}
        }
        if (ni != null) try { s.networkInterface = ni } catch (_: Exception) {}
        sock = s

        thread(name = "wsd-recv") {
            val buf = ByteArray(8192)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val text = String(p.data, 0, p.length)
                    if (text.contains("Probe") && !text.contains("ProbeMatches")) {
                        val rel = Regex("MessageID>\\s*([^<\\s]+)").find(text)?.groupValues?.get(1) ?: ""
                        val data = probeMatches(rel).toByteArray()
                        s.send(DatagramPacket(data, data.size, p.address, p.port))
                    }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }
        thread(name = "wsd-hello") {
            while (running) {
                sendMulticast(hello("Hello"))
                try { Thread.sleep(20000) } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        if (running) sendMulticast(hello("Bye"))
        running = false
        try { sock?.close() } catch (_: Exception) {}
    }

    private fun sendMulticast(xml: String) {
        try {
            val data = xml.toByteArray()
            sock?.send(DatagramPacket(data, data.size, group, 3702))
        } catch (_: Exception) {
        }
    }

    private fun xaddr() = "http://${Util.localIp()}:$onvifPort/onvif/device_service"

    private fun ns() = "xmlns:e='http://www.w3.org/2003/05/soap-envelope' " +
        "xmlns:w='http://schemas.xmlsoap.org/ws/2004/08/addressing' " +
        "xmlns:d='http://schemas.xmlsoap.org/ws/2005/04/discovery' " +
        "xmlns:dn='http://www.onvif.org/ver10/network/wsdl'"

    private fun probeMatches(relatesTo: String) =
        """<?xml version="1.0" encoding="UTF-8"?><e:Envelope ${ns()}><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:RelatesTo>$relatesTo</w:RelatesTo><w:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</w:Action></e:Header><e:Body><d:ProbeMatches><d:ProbeMatch><w:EndpointReference><w:Address>urn:uuid:${info.uuid}</w:Address></w:EndpointReference><d:Types>dn:NetworkVideoTransmitter</d:Types><d:Scopes>${Util.scopes(info)}</d:Scopes><d:XAddrs>${xaddr()}</d:XAddrs><d:MetadataVersion>1</d:MetadataVersion></d:ProbeMatch></d:ProbeMatches></e:Body></e:Envelope>"""

    private fun hello(kind: String) =
        """<?xml version="1.0" encoding="UTF-8"?><e:Envelope ${ns()}><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/$kind</w:Action></e:Header><e:Body><d:$kind><w:EndpointReference><w:Address>urn:uuid:${info.uuid}</w:Address></w:EndpointReference><d:Types>dn:NetworkVideoTransmitter</d:Types><d:Scopes>${Util.scopes(info)}</d:Scopes><d:XAddrs>${xaddr()}</d:XAddrs><d:MetadataVersion>1</d:MetadataVersion></d:$kind></e:Body></e:Envelope>"""
}
