package com.sasha.pcam

import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Calendar
import java.util.TimeZone
import kotlin.concurrent.thread

class OnvifServer(private val info: DeviceInfo, private val port: Int, private val rtspPort: Int) {
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        server = ServerSocket(port).also { it.reuseAddress = true }
        thread(name = "onvif-accept") {
            while (running) {
                try {
                    val c = server!!.accept()
                    thread(name = "onvif-client") { handle(c) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 30000
            val inp = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            while (running) {
                val req = Util.readLine(inp) ?: break
                if (req.isEmpty()) break
                var len = 0
                while (true) {
                    val h = Util.readLine(inp) ?: return
                    if (h.isEmpty()) break
                    if (h.lowercase().startsWith("content-length:")) len = h.substring(15).trim().toIntOrNull() ?: 0
                }
                val bb = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = inp.read(bb, read, len - read)
                    if (n < 0) return
                    read += n
                }
                val body = String(bb)
                val ip = c.localAddress.hostAddress ?: "0.0.0.0"
                val op = Regex("Body[^>]*>\\s*<(?:\\w+:)?(\\w+)").find(body)?.groupValues?.get(1) ?: ""
                val (code, xml) = if (op.isEmpty()) Pair("200 OK", "PhoneCam") else respond(op, ip)
                val data = xml.toByteArray()
                val head = "HTTP/1.1 $code\r\nContent-Type: application/soap+xml; charset=utf-8\r\n" +
                    "Content-Length: ${data.size}\r\nConnection: keep-alive\r\n\r\n"
                out.write(head.toByteArray())
                out.write(data)
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun env(body: String) =
        """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema"><s:Body>$body</s:Body></s:Envelope>"""

    private fun profile(tag: String): String {
        val w = info.w
        val h = info.h
        val f = info.fps
        val br = (w.toLong() * h * f * 0.09).toLong().coerceAtMost(40_000_000L) / 1000
        return """<trt:$tag token="p0" fixed="true"><tt:Name>main</tt:Name><tt:VideoSourceConfiguration token="vsc0"><tt:Name>vsc</tt:Name><tt:UseCount>1</tt:UseCount><tt:SourceToken>vs0</tt:SourceToken><tt:Bounds x="0" y="0" width="$w" height="$h"/></tt:VideoSourceConfiguration><tt:VideoEncoderConfiguration token="vec0"><tt:Name>enc</tt:Name><tt:UseCount>1</tt:UseCount><tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>$w</tt:Width><tt:Height>$h</tt:Height></tt:Resolution><tt:Quality>5</tt:Quality><tt:RateControl><tt:FrameRateLimit>$f</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>$br</tt:BitrateLimit></tt:RateControl><tt:H264><tt:GovLength>$f</tt:GovLength><tt:H264Profile>Main</tt:H264Profile></tt:H264><tt:Multicast><tt:Address><tt:Type>IPv4</tt:Type><tt:IPv4Address>0.0.0.0</tt:IPv4Address></tt:Address><tt:Port>0</tt:Port><tt:TTL>1</tt:TTL><tt:AutoStart>false</tt:AutoStart></tt:Multicast><tt:SessionTimeout>PT60S</tt:SessionTimeout></tt:VideoEncoderConfiguration></trt:$tag>"""
    }

    private fun respond(op: String, ip: String): Pair<String, String> {
        val dev = "http://$ip:$port/onvif/device_service"
        val med = "http://$ip:$port/onvif/media_service"
        val body: String = when (op) {
            "GetSystemDateAndTime" -> {
                val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                """<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:DateTimeType>NTP</tt:DateTimeType><tt:DaylightSavings>false</tt:DaylightSavings><tt:TimeZone><tt:TZ>UTC0</tt:TZ></tt:TimeZone><tt:UTCDateTime><tt:Time><tt:Hour>${c.get(Calendar.HOUR_OF_DAY)}</tt:Hour><tt:Minute>${c.get(Calendar.MINUTE)}</tt:Minute><tt:Second>${c.get(Calendar.SECOND)}</tt:Second></tt:Time><tt:Date><tt:Year>${c.get(Calendar.YEAR)}</tt:Year><tt:Month>${c.get(Calendar.MONTH) + 1}</tt:Month><tt:Day>${c.get(Calendar.DAY_OF_MONTH)}</tt:Day></tt:Date></tt:UTCDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>"""
            }
            "GetDeviceInformation" ->
                """<tds:GetDeviceInformationResponse><tds:Manufacturer>PhoneCam</tds:Manufacturer><tds:Model>${info.name}</tds:Model><tds:FirmwareVersion>0.1</tds:FirmwareVersion><tds:SerialNumber>${info.uuid}</tds:SerialNumber><tds:HardwareId>android</tds:HardwareId></tds:GetDeviceInformationResponse>"""
            "GetCapabilities" ->
                """<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Device><tt:XAddr>$dev</tt:XAddr></tt:Device><tt:Media><tt:XAddr>$med</tt:XAddr><tt:StreamingCapabilities><tt:RTPMulticast>false</tt:RTPMulticast><tt:RTP_TCP>true</tt:RTP_TCP><tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP></tt:StreamingCapabilities></tt:Media></tds:Capabilities></tds:GetCapabilitiesResponse>"""
            "GetServices" ->
                """<tds:GetServicesResponse><tds:Service><tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace><tds:XAddr>$dev</tds:XAddr><tds:Version><tt:Major>2</tt:Major><tt:Minor>0</tt:Minor></tds:Version></tds:Service><tds:Service><tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace><tds:XAddr>$med</tds:XAddr><tds:Version><tt:Major>2</tt:Major><tt:Minor>0</tt:Minor></tds:Version></tds:Service></tds:GetServicesResponse>"""
            "GetScopes" -> {
                val items = Util.scopes(info).split(" ")
                "<tds:GetScopesResponse>" + items.joinToString("") {
                    "<tds:Scopes><tt:ScopeDef>Fixed</tt:ScopeDef><tt:ScopeItem>$it</tt:ScopeItem></tds:Scopes>"
                } + "</tds:GetScopesResponse>"
            }
            "GetProfiles" -> "<trt:GetProfilesResponse>${profile("Profiles")}</trt:GetProfilesResponse>"
            "GetProfile" -> "<trt:GetProfileResponse>${profile("Profile")}</trt:GetProfileResponse>"
            "GetStreamUri" ->
                """<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>rtsp://$ip:$rtspPort/live</tt:Uri><tt:InvalidAfterConnect>false</tt:InvalidAfterConnect><tt:InvalidAfterReboot>false</tt:InvalidAfterReboot><tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetStreamUriResponse>"""
            "GetVideoSources" ->
                """<trt:GetVideoSourcesResponse><trt:VideoSources token="vs0"><tt:Framerate>${info.fps}</tt:Framerate><tt:Resolution><tt:Width>${info.w}</tt:Width><tt:Height>${info.h}</tt:Height></tt:Resolution></trt:VideoSources></trt:GetVideoSourcesResponse>"""
            else -> return Pair(
                "500 Internal Server Error",
                env("""<s:Fault><s:Code><s:Value>s:Receiver</s:Value></s:Code><s:Reason><s:Text xml:lang="en">Action not supported</s:Text></s:Reason></s:Fault>""")
            )
        }
        return Pair("200 OK", env(body))
    }
}
