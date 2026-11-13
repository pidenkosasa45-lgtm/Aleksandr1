package com.sasha.pcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import java.util.UUID
import kotlin.concurrent.thread

class StreamService : Service() {
    companion object {
        @Volatile var instance: StreamService? = null
        @Volatile var running = false
        @Volatile var status = "Остановлено"
        @Volatile var url = ""
        @Volatile var fpsNow = 0
    }

    private var rtsp: RtspServer? = null
    private var onvif: OnvifServer? = null
    private var disc: WsDiscovery? = null
    private var cam: CameraStreamer? = null
    private var info: DeviceInfo? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var mcast: WifiManager.MulticastLock? = null
    private var w = 1920
    private var h = 1080
    private var fps = 60
    private var ev = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("pcam", "PhoneCam", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "pcam")
            .setContentTitle("PhoneCam")
            .setContentText("Камера транслируется на ПК")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(1, n)

        if (running) return START_NOT_STICKY
        w = intent?.getIntExtra("w", 1920) ?: 1920
        h = intent?.getIntExtra("h", 1080) ?: 1080
        fps = intent?.getIntExtra("fps", 60) ?: 60
        ev = intent?.getIntExtra("ev", 0) ?: 0
        running = true
        instance = this
        acquireLocks()
        thread { startAll(fps) }
        return START_NOT_STICKY
    }

    @Synchronized
    private fun startAll(fpsReq: Int) {
        try {
            val ip = Util.localIp()
            val dinfo = info ?: run {
                val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "pcam"
                val uuid = UUID.nameUUIDFromBytes(("pcam-$id").toByteArray()).toString()
                DeviceInfo("PhoneCam-" + uuid.takeLast(4), uuid, w, h, fpsReq)
            }
            info = dinfo
            dinfo.w = w; dinfo.h = h; dinfo.fps = fpsReq

            val r = RtspServer(8554)
            r.start()
            rtsp = r

            val c = CameraStreamer(this, r, w, h, fpsReq, ev) { st -> status = st }
            c.onActual = { aw, ah, af -> dinfo.w = aw; dinfo.h = ah; dinfo.fps = af }
            c.onConfigFailed = {
                if (fpsReq > 30 && running) {
                    thread {
                        stopAll()
                        status = "$fpsReq fps не поддерживается в этом режиме, переключаюсь на 30"
                        startAll(30)
                    }
                }
            }
            r.onNeedKeyFrame = { c.requestKey() }
            cam = c
            c.start()

            val o = OnvifServer(dinfo, 8080, 8554)
            o.start()
            onvif = o
            try {
                val d = WsDiscovery(dinfo, 8080)
                d.start()
                disc = d
            } catch (e: Exception) {
                status = "Обнаружение не запустилось: ${e.message}. Можно подключаться по адресу RTSP."
            }
            url = "rtsp://$ip:8554/live"
        } catch (e: Exception) {
            status = "Ошибка запуска: ${e.message}"
        }
    }

    @Synchronized
    private fun stopAll() {
        try { disc?.stop() } catch (_: Exception) {}
        try { onvif?.stop() } catch (_: Exception) {}
        try { cam?.stop() } catch (_: Exception) {}
        try { rtsp?.stop() } catch (_: Exception) {}
        disc = null; onvif = null; cam = null; rtsp = null
    }

    fun setEv(v: Int) {
        ev = v
        cam?.setEv(v)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pcam:wake").also { it.acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi = wm.createWifiLock(mode, "pcam:wifi").also { it.acquire() }
        mcast = wm.createMulticastLock("pcam:mcast").also { it.acquire() }
    }

    override fun onDestroy() {
        running = false
        stopAll()
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        try { mcast?.release() } catch (_: Exception) {}
        status = "Остановлено"
        url = ""
        fpsNow = 0
        instance = null
        super.onDestroy()
    }
}
