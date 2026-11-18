package com.mipad.gpstotablet

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etTabletIp: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvCoords: TextView
    private lateinit var btnToggle: Button

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val lat = intent?.getDoubleExtra("lat", 0.0) ?: 0.0
            val lng = intent?.getDoubleExtra("lng", 0.0) ?: 0.0
            val acc = intent?.getFloatExtra("accuracy", 0f) ?: 0f
            val speed = intent?.getFloatExtra("speed", 0f) ?: 0f

            tvCoords.text = "Широта: %.6f\nДолгота: %.6f\nТочность: ±%.1f м\nСкорость: %.1f км/ч".format(
                lat, lng, acc, speed
            )
            tvStatus.text = "Статус: Трансляция активна 🟢"
            tvStatus.setTextColor(0xFF4CAF50.toInt())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etTabletIp = findViewById(R.id.etTabletIp)
        tvStatus = findViewById(R.id.tvStatus)
        tvCoords = findViewById(R.id.tvCoords)
        btnToggle = findViewById(R.id.btnToggle)

        val prefs = getSharedPreferences("gps_prefs", Context.MODE_PRIVATE)
        etTabletIp.setText(prefs.getString("tablet_ip", "192.168.43.1"))

        updateUiState()

        btnToggle.setOnClickListener {
            if (GpsForegroundService.isRunning) {
                stopService()
            } else {
                checkPermissionsAndStart()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        } else {
            startService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startService()
        } else {
            Toast.makeText(this, "Требуется разрешение на доступ к геопозиции", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startService() {
        val ip = etTabletIp.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, "Введите IP адрес планшета", Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences("gps_prefs", Context.MODE_PRIVATE).edit()
            .putString("tablet_ip", ip)
            .apply()

        val intent = Intent(this, GpsForegroundService::class.java).apply {
            action = GpsForegroundService.ACTION_START
            putExtra(GpsForegroundService.EXTRA_IP, ip)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateUiState()
    }

    private fun stopService() {
        val intent = Intent(this, GpsForegroundService::class.java).apply {
            action = GpsForegroundService.ACTION_STOP
        }
        startService(intent)
        updateUiState()
    }

    private fun updateUiState() {
        if (GpsForegroundService.isRunning) {
            btnToggle.text = "ОСТАНОВИТЬ ТРАНСЛЯЦИЮ"
            btnToggle.backgroundTintList = getColorStateList(android.R.color.holo_red_dark)
            tvStatus.text = "Статус: Трансляция активна 🟢"
            tvStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            btnToggle.text = "ЗАПУСТИТЬ ТРАНСЛЯЦИЮ"
            btnToggle.backgroundTintList = getColorStateList(android.R.color.holo_orange_light)
            tvStatus.text = "Статус: Остановлено ⚪"
            tvStatus.setTextColor(0xFFFF5252.toInt())
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(locationReceiver, IntentFilter(GpsForegroundService.BROADCAST_LOCATION), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(locationReceiver, IntentFilter(GpsForegroundService.BROADCAST_LOCATION))
        }
        updateUiState()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(locationReceiver)
        } catch (e: Exception) {}
    }
}
