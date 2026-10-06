package com.example.sosapp

import android.app.*
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*

class SOSService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var phoneNumber: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        phoneNumber = intent?.getStringExtra("number") ?: ""

        startForeground(1, createNotification())

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()

        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "SOSChannel"

        val channel = NotificationChannel(
            channelId,
            "SOS Service",
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("SOS Protection Active")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
    }

    private fun startLocationUpdates() {

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation
                if (location != null) {
                    sendSOS(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(request, callback, null)
    }

    private fun sendSOS(location: Location) {

        val lat = location.latitude
        val lon = location.longitude

        val msg = "🚨 HELP!\nhttps://maps.google.com/?q=$lat,$lon"

        try {
            SmsManager.getDefault().sendTextMessage(phoneNumber, null, msg, null, null)
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}