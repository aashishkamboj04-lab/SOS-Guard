package com.example.sosapp

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class SOSBackgroundService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private var isCandidateDetected = false
    private var candidateTimestamp: Long = 0
    private var maxWindowMagnitude: Float = 0f
    private var lastTriggerTime: Long = 0

    companion object {
        const val TAG = "SOSAccidentDetector"
        const val ACCIDENT_IMPACT_THRESHOLD = 30.0f
        const val ACCIDENT_MOTION_THRESHOLD = 16.0f
        const val ACCIDENT_CONFIRMATION_WINDOW_MS = 400L
        const val ACCIDENT_COOLDOWN_MS = 15000L
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        startForeground(1, createNotification())
        registerSensor()
    }

    private fun registerSensor() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun createNotification(): Notification {
        val channelId = "SOSBackgroundChannel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SOS Accident Detection",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("SOS Guard")
            .setContentText("SOS Guard accident detection is active")
            .setSmallIcon(R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTriggerTime < ACCIDENT_COOLDOWN_MS) {
                return
            }

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()

            if (!isCandidateDetected) {
                if (magnitude > ACCIDENT_IMPACT_THRESHOLD) {
                    Log.d(TAG, "Impact candidate detected")
                    isCandidateDetected = true
                    candidateTimestamp = currentTime
                    maxWindowMagnitude = magnitude
                } else {
                    if (magnitude > 20f) {
                        Log.d(TAG, "Impact rejected: below threshold")
                    }
                }
            } else {
                // Stage 2: Confirmation window
                if (currentTime - candidateTimestamp <= ACCIDENT_CONFIRMATION_WINDOW_MS) {
                    if (magnitude > maxWindowMagnitude) {
                        maxWindowMagnitude = magnitude
                    }
                } else {
                    // Window closed, check confirmation criteria
                    if (maxWindowMagnitude >= ACCIDENT_IMPACT_THRESHOLD && maxWindowMagnitude >= ACCIDENT_MOTION_THRESHOLD) {
                        Log.d(TAG, "Accident confirmed")
                        lastTriggerTime = currentTime
                        isCandidateDetected = false
                        triggerCountdownInForeground()
                    } else {
                        Log.d(TAG, "Impact rejected: insufficient confirmation")
                        isCandidateDetected = false
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun triggerCountdownInForeground() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("START_ACCIDENT_COUNTDOWN", true)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity for countdown", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }
}
