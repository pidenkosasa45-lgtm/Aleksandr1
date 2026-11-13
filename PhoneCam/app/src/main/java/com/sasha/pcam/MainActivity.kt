package com.sasha.pcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private val resNames = arrayOf("2560x1440 (2K)", "1920x1080 (Full HD)", "1280x720 (HD)")
    private val resW = intArrayOf(2560, 1920, 1280)
    private val resH = intArrayOf(1440, 1080, 720)
    private val fpsVals = intArrayOf(60, 30, 15)
    private val fpsNames = arrayOf("60 fps", "30 fps", "15 fps (ночной режим)")

    private lateinit var statusTv: TextView
    private lateinit var btn: Button
    private lateinit var resSp: Spinner
    private lateinit var fpsSp: Spinner
    private lateinit var evTv: TextView
    private var ev = 0
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }
        root.addView(TextView(this).apply { text = "PhoneCam"; textSize = 30f })
        root.addView(TextView(this).apply { text = "Телефон как камера для Windows по Wi-Fi"; textSize = 15f })

        root.addView(label("Разрешение"))
        resSp = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, resNames); setSelection(1) }
        root.addView(resSp)

        root.addView(label("Кадры в секунду"))
        fpsSp = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, fpsNames) }
        root.addView(fpsSp)

        evTv = label("Яркость (EV): 0")
        root.addView(evTv)
        root.addView(SeekBar(this).apply {
            max = 24
            progress = 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    ev = p - 12
                    evTv.text = "Яркость (EV): $ev"
                    StreamService.instance?.setEv(ev)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        btn = Button(this).apply {
            text = "Запустить"
            setOnClickListener { if (StreamService.running) stopStream() else startStream() }
        }
        root.addView(btn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        statusTv = TextView(this).apply { textSize = 15f; gravity = Gravity.START; setPadding(0, dp(16), 0, dp(16)) }
        root.addView(statusTv)

        root.addView(Button(this).apply {
            text = "Экономия батареи: настроить"
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })
        root.addView(TextView(this).apply {
            text = "Чтобы камера не пропадала: для PhoneCam выбери «Без ограничений» и включи автозапуск."
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, dp(16), 0, dp(4)) }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        btn.text = if (StreamService.running) "Остановить" else "Запустить"
        val sb = StringBuilder(StreamService.status)
        if (StreamService.running && StreamService.url.isNotEmpty()) {
            sb.append("\n\nRTSP: ").append(StreamService.url)
            sb.append("\n\nWindows: Параметры → Bluetooth и устройства → Камеры → Добавить устройство")
        }
        statusTv.text = sb.toString()
    }

    private fun startStream() {
        val perms = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) {
            requestPermissions(perms.toTypedArray(), 1)
            return
        }
        launch()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launch()
        else StreamService.status = "Нужно разрешение на камеру"
    }

    private fun launch() {
        val i = Intent(this, StreamService::class.java)
            .putExtra("w", resW[resSp.selectedItemPosition])
            .putExtra("h", resH[resSp.selectedItemPosition])
            .putExtra("fps", fpsVals[fpsSp.selectedItemPosition])
            .putExtra("ev", ev)
        StreamService.status = "Запускаю..."
        startForegroundService(i)
    }

    private fun stopStream() {
        stopService(Intent(this, StreamService::class.java))
    }
}
