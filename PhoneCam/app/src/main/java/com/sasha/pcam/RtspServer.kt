package com.sasha.pcam

import android.util.Base64
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class RtspServer(private val port: Int) {
    @Volatile var sps: ByteArray? = null
    @Volatile var pps: ByteArray? = null
    @Volatile var onNeedKeyFrame: (() -> Unit)? = null

    private class Session(val id: String) {
        var tcp: OutputStream? = null
        var chan = 0
        var udp: DatagramSocket? = null
        var dst: InetAddress? = null
        var dstPort = 0
        @Volatile var playing = false
    }

    private var server: ServerSocket? = null
    private val sessions = CopyOnWriteArrayList<Session>()
    @Volatile private var running = false
    private var seq = 0
    private val ssrc = 0x1234abcd
    private val mtu = 1400

    val clients: Int get() = sessions.count { it.playing }

    fun start() {
        running = true
        server = ServerSocket(port).also { it.reuseAddress = true }
        thread(name = "rtsp-accept") {
            while (running) {
                try {
                    val c = server!!.accept()
                    thread(name = "rtsp-client") { handleClient(c) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        sessions.forEach { it.udp?.close() }
        sessions.clear()
    }

    /** Called with the codec config (SPS/PPS in Annex B). */
    fun setConfig(data: ByteArray) {
        for (n in Util.splitNals(data)) {
            if (n.isEmpty()) continue
            when (n[0].toInt() and 0x1f) {
                7 -> sps = n
                8 -> pps = n
            }
        }
    }

    private fun reply(out: OutputStream, cseq: String, extra: String = "", body: String = "", code: String = "200 OK") {
        val sb = StringBuilder("RTSP/1.0 $code\r\nCSeq: $cseq\r\nServer: PhoneCam\r\n$extra")
        val bytes = body.toByteArray()
        if (bytes.isNotEmpty()) sb.append("Content-Length: ${bytes.size}\r\n")
        sb.append("\r\n")
        synchronized(out) {
            out.write(sb.toString().toByteArray())
            if (bytes.isNotEmpty()) out.write(bytes)
            out.flush()
        }
    }

    private fun handleClient(sock: Socket) {
        val mine = ArrayList<Session>()
        try {
            sock.tcpNoDelay = true
            val inp = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            while (running) {
                val b = inp.read()
                if (b < 0) break
                if (b == '$'.code) { // interleaved RTCP from client: skip
                    inp.read()
                    var left = (inp.read() shl 8) or inp.read()
                    while (left > 0) {
                        if (inp.read() < 0) return
                        left--
                    }
                    continue
                }
                val line = Util.readLine(inp, b) ?: break
                if (line.isBlank()) continue
                val method = line.split(" ")[0]
                val headers = HashMap<String, String>()
                while (true) {
                    val h = Util.readLine(inp) ?: return
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                }
                headers["content-length"]?.toIntOrNull()?.let {
                    var n = it
                    while (n > 0) { if (inp.read() < 0) return; n-- }
                }
                val cseq = headers["cseq"] ?: "0"
                val ip = sock.localAddress.hostAddress ?: "0.0.0.0"
                when (method) {
                    "OPTIONS" -> reply(out, cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER\r\n")
                    "DESCRIBE" -> {
                        var tries = 0
                        while ((sps == null || pps == null) && tries < 30) { Thread.sleep(100); tries++ }
                        val s = sps
                        val p = pps
                        if (s == null || p == null || s.size < 4) {
                            reply(out, cseq, code = "503 Service Unavailable")
                        } else {
                            val pli = "%02x%02x%02x".format(s[1], s[2], s[3])
                            val sdp = "v=0\r\no=- 0 0 IN IP4 $ip\r\ns=PhoneCam\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n" +
                                "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
                                "a=fmtp:96 packetization-mode=1;profile-level-id=$pli;sprop-parameter-sets=" +
                                Base64.encodeToString(s, Base64.NO_WRAP) + "," +
                                Base64.encodeToString(p, Base64.NO_WRAP) + "\r\n" +
                                "a=control:streamid=0\r\n"
                            reply(out, cseq, "Content-Base: rtsp://$ip:$port/live/\r\nContent-Type: application/sdp\r\n", sdp)
                        }
                    }
                    "SETUP" -> {
                        val tr = headers["transport"] ?: ""
                        val s = Session(java.lang.Long.toHexString(System.nanoTime()))
                        val transport: String
                        if (tr.contains("TCP")) {
                            val m = Regex("interleaved=(\\d+)-(\\d+)").find(tr)
                            val a = m?.groupValues?.get(1)?.toInt() ?: 0
                            s.tcp = out
                            s.chan = a
                            transport = "RTP/AVP/TCP;unicast;interleaved=$a-${a + 1}"
                        } else {
                            val m = Regex("client_port=(\\d+)-(\\d+)").find(tr)
                            if (m == null) { reply(out, cseq, code = "461 Unsupported Transport"); continue }
                            val a = m.groupValues[1].toInt()
                            val u = DatagramSocket()
                            s.udp = u
                            s.dst = sock.inetAddress
                            s.dstPort = a
                            transport = "RTP/AVP;unicast;client_port=$a-${a + 1};server_port=${u.localPort}-${u.localPort + 1}"
                        }
                        mine.add(s)
                        sessions.add(s)
                        reply(out, cseq, "Session: ${s.id};timeout=60\r\nTransport: $transport\r\n")
                    }
                    "PLAY" -> {
                        mine.forEach { it.playing = true }
                        onNeedKeyFrame?.invoke()
                        reply(out, cseq, "Range: npt=0.000-\r\nSession: ${mine.lastOrNull()?.id ?: "0"}\r\n")
                    }
                    "PAUSE", "GET_PARAMETER", "SET_PARAMETER" -> reply(out, cseq, "Session: ${mine.lastOrNull()?.id ?: "0"}\r\n")
                    "TEARDOWN" -> {
                        mine.forEach { it.playing = false; sessions.remove(it); it.udp?.close() }
                        reply(out, cseq)
                    }
                    else -> reply(out, cseq, code = "405 Method Not Allowed")
                }
            }
        } catch (_: Exception) {
        } finally {
            mine.forEach { sessions.remove(it); it.udp?.close() }
            try { sock.close() } catch (_: Exception) {}
        }
    }

    // ---- RTP sending ----

    private fun header(marker: Boolean, ts: Int): ByteArray {
        val h = ByteArray(12)
        h[0] = 0x80.toByte()
        h[1] = ((if (marker) 0x80 else 0) or 96).toByte()
        h[2] = (seq shr 8).toByte(); h[3] = seq.toByte()
        seq = (seq + 1) and 0xffff
        h[4] = (ts shr 24).toByte(); h[5] = (ts shr 16).toByte(); h[6] = (ts shr 8).toByte(); h[7] = ts.toByte()
        h[8] = (ssrc shr 24).toByte(); h[9] = (ssrc shr 16).toByte(); h[10] = (ssrc shr 8).toByte(); h[11] = ssrc.toByte()
        return h
    }

    private fun send(pkt: ByteArray) {
        for (s in sessions) {
            if (!s.playing) continue
            try {
                val t = s.tcp
                if (t != null) {
                    synchronized(t) {
                        t.write(byteArrayOf('$'.code.toByte(), s.chan.toByte(), (pkt.size shr 8).toByte(), pkt.size.toByte()))
                        t.write(pkt)
                    }
                } else {
                    s.udp?.send(DatagramPacket(pkt, pkt.size, s.dst, s.dstPort))
                }
            } catch (_: Exception) {
                s.playing = false
                sessions.remove(s)
            }
        }
    }

    @Synchronized
    fun sendFrame(annexB: ByteArray, ptsUs: Long, key: Boolean) {
        if (sessions.none { it.playing }) return
        val ts = ((ptsUs * 9) / 100).toInt()
        val nals = ArrayList<ByteArray>()
        if (key) {
            sps?.let { nals.add(it) }
            pps?.let { nals.add(it) }
        }
        for (n in Util.splitNals(annexB)) {
            if (n.isEmpty()) continue
            val type = n[0].toInt() and 0x1f
            if (type == 9 || type == 7 || type == 8) continue
            nals.add(n)
        }
        for ((idx, nal) in nals.withIndex()) {
            val last = idx == nals.lastIndex
            if (nal.size <= mtu) {
                send(header(last, ts) + nal)
            } else {
                val indicator = ((nal[0].toInt() and 0xE0) or 28).toByte()
                val type = nal[0].toInt() and 0x1f
                var pos = 1
                while (pos < nal.size) {
                    val len = minOf(mtu - 2, nal.size - pos)
                    val start = pos == 1
                    val end = pos + len >= nal.size
                    val fuHeader = ((if (start) 0x80 else 0) or (if (end) 0x40 else 0) or type).toByte()
                    val pkt = ByteArray(12 + 2 + len)
                    System.arraycopy(header(last && end, ts), 0, pkt, 0, 12)
                    pkt[12] = indicator
                    pkt[13] = fuHeader
                    System.arraycopy(nal, pos, pkt, 14, len)
                    send(pkt)
                    pos += len
                }
            }
        }
        for (s in sessions) {
            val t = s.tcp ?: continue
            try { synchronized(t) { t.flush() } } catch (_: Exception) { s.playing = false; sessions.remove(s) }
        }
    }
}
