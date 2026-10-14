package org.wirl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps dictation allowed while the user is in another app. Android only lets an app use the microphone in
 * the background while a microphone foreground service is running, and this is what that service is.
 */
class DictationService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.dictation_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wirl_glyph)
            .setContentTitle(getString(R.string.dictation_notification_title))
            .setContentText(getString(R.string.dictation_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Without this the app would crash. Stopping lets the bubble report the mic as blocked.
            Log.e("Wirl", "Couldn't start the microphone service", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "dictation"
        private const val NOTIFICATION_ID = 2
    }
}
