package com.mipad.gpstotablet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class GpsForegroundService : Service(), LocationListener {

    private var locationManager: LocationManager? = null
    private var tabletIp: String = "192.168.43.1"
    private val networkExecutor = Executors.newSingleThreadExecutor()

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_IP = "EXTRA_IP"
        const val CHANNEL_ID = "gps_to_tablet_channel"
        const val BROADCAST_LOCATION = "com.mipad.gpstotablet.LOCATION_UPDATE"
        var isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        tabletIp = intent?.getStringExtra(EXTRA_IP) ?: "192.168.43.1"
        startAsForeground()
        startLocationUpdates()
        isRunning = true
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS на Планшет активен")
            .setContentText("Трансляция геопозиции на $tabletIp:8088")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun startLocationUpdates() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                this
            )
            // Также слушаем Network Provider как резерв
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1000L,
                0f,
                this
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun onLocationChanged(location: Location) {
        val speedKmH = location.speed * 3.6f
        val updateIntent = Intent(BROADCAST_LOCATION).apply {
            putExtra("lat", location.latitude)
            putExtra("lng", location.longitude)
            putExtra("accuracy", location.accuracy)
            putExtra("speed", speedKmH)
            setPackage(packageName)
        }
        sendBroadcast(updateIntent)

        // Отправка на планшет в фоновом потоке
        networkExecutor.execute {
            sendGpsToTablet(location)
        }
    }

    private fun sendGpsToTablet(loc: Location) {
        try {
            val url = URL("http://$tabletIp:8088/api/gps")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.doOutput = true

            val json = JSONObject().apply {
                put("lat", loc.latitude)
                put("lng", loc.longitude)
                put("accuracy", loc.accuracy)
                put("speed", loc.speed)
                put("bearing", loc.bearing)
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(json.toString())
                writer.flush()
            }

            conn.responseCode // Читаем ответ
            conn.disconnect()
        } catch (e: Exception) {
            // Игнорируем сетевые сбои отдельных пакетов
        }
    }

    private fun stopForegroundService() {
        isRunning = false
        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopForegroundService()
        super.onDestroy()
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Канал для службы передачи GPS на планшет"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
