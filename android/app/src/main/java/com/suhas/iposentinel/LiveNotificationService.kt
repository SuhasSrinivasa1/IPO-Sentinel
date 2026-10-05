package com.suhas.iposentinel

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Retained only for binary/source compatibility with older installs.
 * v1.3.2 has no foreground polling loop and never talks to BackendApi.
 */
class LiveNotificationService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppAudit.log(this, "LEGACY_LIVE_SERVICE_START_BLOCKED")
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.suhas.iposentinel.STOP_LIVE_NOTIFICATION_SERVICE"
    }
}
