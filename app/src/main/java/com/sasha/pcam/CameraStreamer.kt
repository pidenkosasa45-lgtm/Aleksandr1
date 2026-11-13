package com.sasha.pcam

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface

class CameraStreamer(
    private val ctx: Context,
    private val server: RtspServer,
    private val reqW: Int,
    private val reqH: Int,
    private val reqFps: Int,
    private var ev: Int,
    private val onStatus: (String) -> Unit
) {
    var onActual: ((Int, Int, Int) -> Unit)? = null
    var onConfigFailed: (() -> Unit)? = null

    private val thread = HandlerThread("cam").also { it.start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var surface: Surface? = null
    private var evRange: Range<Int>? = null
    private var fpsRange: Range<Int>? = null
    private var highSpeed = false
    private var experimental = false
    private var chars: CameraCharacteristics? = null
    private var fCount = 0
    private var fT = System.currentTimeMillis()
    private var diag = ""
    @Volatile private var stopped = false

    @SuppressLint("MissingPermission")
    fun start() {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: mgr.cameraIdList[0]
        val ch = mgr.getCameraCharacteristics(id)
        chars = ch

        // fps range (normal mode)
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val exact = ranges.firstOrNull { it.lower == reqFps && it.upper == reqFps }
        var range = exact
            ?: ranges.filter { it.upper >= reqFps }.minWithOrNull(compareBy({ it.upper }, { -it.lower }))
            ?: ranges.maxByOrNull { it.upper }

        // size
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaCodec::class.java) ?: emptyArray()
        var size = if (sizes.any { it.width == reqW && it.height == reqH }) Size(reqW, reqH)
        else sizes.filter { it.width <= reqW && it.height <= reqH && it.width * 9 == it.height * 16 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= reqW && it.height <= reqH }.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)

        // high speed mode, if normal mode cannot give the requested fps
        val hsInfo = StringBuilder()
        try {
            map?.highSpeedVideoSizes?.forEach { sz ->
                val rs = map.getHighSpeedVideoFpsRangesFor(sz)
                hsInfo.append("${sz.width}x${sz.height}:").append(rs.joinToString("") { "[${it.lower}-${it.upper}]" }).append(" ")
            }
            if (reqFps > 30 && ranges.none { it.upper >= reqFps } && map != null) {
                val hsSize = map.highSpeedVideoSizes.firstOrNull { it.width == reqW && it.height == reqH }
                if (hsSize != null) {
                    val r = map.getHighSpeedVideoFpsRangesFor(hsSize).filter { it.upper == reqFps }.maxByOrNull { it.lower }
                    if (r != null) {
                        highSpeed = true
                        range = r
                        size = hsSize
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (reqFps > 30 && !highSpeed && (range?.upper ?: 0) < reqFps) {
            experimental = true
            range = Range(reqFps, reqFps)
        }
        fpsRange = range
        val encFps = if (experimental) reqFps else minOf(reqFps, range?.upper ?: reqFps)
        val vendor = try {
            ch.availableCaptureRequestKeys.map { it.name }.filter {
                val n = it.lowercase()
                n.contains("fps") || n.contains("hfr") || n.contains("highspeed") || n.contains("high_speed") ||
                    n.contains("streamingfeature") || n.contains("slow") || n.contains("60")
            }
        } catch (_: Exception) { emptyList() }
        val cams = try {
            mgr.cameraIdList.joinToString(" ") { cid ->
                val c = mgr.getCameraCharacteristics(cid)
                val mx = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxOfOrNull { it.upper }
                "$cid:${c.get(CameraCharacteristics.LENS_FACING)}/$mx"
            }
        } catch (_: Exception) { "?" }
        diag = "Обычные fps: " + ranges.joinToString(" ") { "[${it.lower}-${it.upper}]" } +
            "\nКамеры(id:сторона/макс fps): $cams" +
            "\nКлючи: " + (if (vendor.isEmpty()) "нет" else vendor.joinToString(", ")) +
            (if (experimental) "\nРежим: эксперимент (MTK-ключ)" else "") +
            "\nHigh-speed: " + (if (hsInfo.isEmpty()) "нет" else hsInfo.toString().trim()) +
            (if (highSpeed) "\nРежим: high-speed" else "")

        evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)

        val bitrate = (size.width.toLong() * size.height * encFps * 0.09).toLong().coerceAtMost(40_000_000L).toInt()
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, encFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(data)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            server.setConfig(data)
                        } else {
                            server.sendFrame(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            fCount++
                            val now = System.currentTimeMillis()
                            if (now - fT >= 1000) {
                                StreamService.fpsNow = (fCount * 1000L / (now - fT)).toInt()
                                fCount = 0
                                fT = now
                            }
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (_: Exception) {
                }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                onStatus("Ошибка кодера: ${e.message}")
            }
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                for (k in arrayOf("csd-0", "csd-1")) {
                    val bb = format.getByteBuffer(k) ?: continue
                    val arr = ByteArray(bb.remaining())
                    bb.duplicate().get(arr)
                    server.setConfig(arr)
                }
            }
        }, handler)
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = c.createInputSurface()
        c.start()
        codec = c

        onActual?.invoke(size.width, size.height, encFps)
        val desc = "${size.width}x${size.height} @ $encFps fps"

        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cam = device
                createSession(device, desc)
            }
            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (!stopped) onStatus("Камера отключена (её занял другой процесс)")
            }
            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                if (!stopped) onStatus("Ошибка камеры: код $error")
            }
        }, handler)
    }

    private var hsSession: CameraConstrainedHighSpeedCaptureSession? = null

    private fun buildRequest(device: CameraDevice, surf: Surface): CaptureRequest.Builder {
        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        b.addTarget(surf)
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        fpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        evRange?.let { b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev.coerceIn(it.lower, it.upper)) }
        if (experimental) {
            try {
                for (k in chars?.availableCaptureRequestKeys ?: emptyList()) {
                    if (k.name == "com.mediatek.streamingfeature.hfpsMode") {
                        @Suppress("UNCHECKED_CAST")
                        b.set(k as CaptureRequest.Key<Int>, 1)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return b
    }

    private fun applyRequest(s: CameraCaptureSession, b: CaptureRequest.Builder) {
        if (highSpeed) {
            val hs = s as CameraConstrainedHighSpeedCaptureSession
            hs.setRepeatingBurst(hs.createHighSpeedRequestList(b.build()), null, handler)
        } else {
            s.setRepeatingRequest(b.build(), null, handler)
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(device: CameraDevice, desc: String) {
        val surf = surface ?: return
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    val b = buildRequest(device, surf)
                    builder = b
                    applyRequest(s, b)
                    onStatus("Камера: $desc\n$diag")
                } catch (e: Exception) {
                    onStatus("Ошибка запуска съёмки: ${e.message}\n$diag")
                    onConfigFailed?.invoke()
                }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (!stopped) {
                    onStatus("Не удалось настроить камеру ($desc)\n$diag")
                    onConfigFailed?.invoke()
                }
            }
        }
        if (highSpeed) device.createConstrainedHighSpeedCaptureSession(listOf(surf), cb, handler)
        else device.createCaptureSession(listOf(surf), cb, handler)
    }

    fun setEv(v: Int) {
        ev = v
        val b = builder ?: return
        val r = evRange ?: return
        try {
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, v.coerceIn(r.lower, r.upper))
            session?.let { applyRequest(it, b) }
        } catch (_: Exception) {
        }
    }

    fun requestKey() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {
        }
    }

    fun stop() {
        stopped = true
        try { session?.close() } catch (_: Exception) {}
        try { cam?.close() } catch (_: Exception) {}
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        thread.quitSafely()
    }
}
