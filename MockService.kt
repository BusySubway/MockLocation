package com.example.mockloc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import kotlin.random.Random

/** 前台服务：每秒向 GPS / 网络 / 融合定位推送一次模拟坐标 */
class MockService : Service() {

    companion object {
        const val PREFS = "mock"
        const val KEY_LAT = "lat"
        const val KEY_LNG = "lng"
        private const val CHANNEL = "mock"
        @Volatile var running = false
    }

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val providers = mutableListOf<String>()
    private var lat = 39.9042
    private var lng = 116.4074

    private val tick = object : Runnable {
        override fun run() {
            push()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: lat
        lng = prefs.getString(KEY_LNG, null)?.toDoubleOrNull() ?: lng

        startInForeground()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager

        if (providers.isEmpty() && !setupProviders()) {
            Toast.makeText(this, "请先在开发者选项里把本应用设为模拟位置应用", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        running = true
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun setupProviders(): Boolean {
        val names = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) names += LocationManager.FUSED_PROVIDER
        for (p in names) {
            try {
                try { lm.removeTestProvider(p) } catch (_: Exception) {}
                // 参数: requiresNetwork, requiresSatellite, requiresCell, hasMonetaryCost,
                //       supportsAltitude, supportsSpeed, supportsBearing, power(LOW=1), accuracy(FINE=1)
                lm.addTestProvider(p, false, false, false, false, true, true, true, 1, 1)
                lm.setTestProviderEnabled(p, true)
                providers += p
            } catch (e: SecurityException) {
                return false          // 未被选为模拟位置应用
            } catch (_: Exception) {
                // 个别机型不支持某个 provider，跳过即可
            }
        }
        return providers.isNotEmpty()
    }

    private fun push() {
        for (p in providers) {
            val loc = Location(p).apply {
                // 极小的随机抖动，让位置看起来更像真实信号
                latitude = lat + (Random.nextDouble() - 0.5) * 0.00004
                longitude = lng + (Random.nextDouble() - 0.5) * 0.00004
                altitude = 50.0
                accuracy = if (p == LocationManager.GPS_PROVIDER) 5f else 20f
                speed = 0f
                bearing = 0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            try { lm.setTestProviderLocation(p, loc) } catch (_: Exception) {}
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "模拟定位", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("模拟定位运行中")
            .setContentText(String.format("%.4f, %.4f", lat, lng))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        for (p in providers) {
            try { lm.setTestProviderEnabled(p, false) } catch (_: Exception) {}
            try { lm.removeTestProvider(p) } catch (_: Exception) {}
        }
        providers.clear()
        running = false
        super.onDestroy()
    }
}
