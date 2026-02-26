package com.resqmesh.app.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MeshService : Service() {

    companion object {
        const val CHANNEL_ID = "ResQMeshServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START_MESH_SERVICE"
        const val ACTION_STOP = "ACTION_STOP_MESH_SERVICE"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // START_STICKY tells Android to recreate the service if it accidentally gets killed
        return START_STICKY
    }

    private fun startForegroundService() {
        // 1. Create the Notification Channel (Required for Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mesh Network Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        // 2. NEW: Create the command for the "STOP" button
        val stopIntent = Intent(this, MeshService::class.java).apply {
            action = ACTION_STOP
        }
        // FLAG_IMMUTABLE is strictly required by Android 12+ for security!
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 3. Build the Sticky Notification
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ResQMesh Network Active")
            .setContentText("Listening for emergency SOS signals...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            // NEW: Add the clickable button to the notification!
            .addAction(android.R.drawable.ic_delete, "STOP NETWORK", stopPendingIntent)
            .build()

        // 4. Start the service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't need binding for this setup
    }
}