package com.suhas.iposentinel

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class BrokerNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        RecoveryScheduler.ensureScheduled(this)
        RecoveryCoordinator.request(this, "NOTIFICATION_LISTENER_RECONNECTED", force = true)
        AppAudit.log(this, "NOTIFICATION_LISTENER_CONNECTED")
    }

    override fun onListenerDisconnected() {
        AppAudit.log(this, "NOTIFICATION_LISTENER_DISCONNECTED")
        runCatching {
            NotificationListenerService.requestRebind(ComponentName(this, BrokerNotificationListenerService::class.java))
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName.orEmpty()
        if (pkg.contains("groww", ignoreCase = true)) {
            RecoveryCoordinator.request(this, "GROWW_NOTIFICATION_WAKE")
        }
    }
}

class RecoveryBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val trigger = when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> "BOOT_COMPLETED"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "PACKAGE_REPLACED"
            else -> "SYSTEM_RECEIVER"
        }
        RecoveryScheduler.ensureScheduled(context)
        RecoveryScheduler.enqueueImmediate(context, trigger)
    }
}

class BrokerRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER) ?: "PERIODIC_WORK"
        val snapshot = RecoveryCoordinator.reconcile(applicationContext, trigger, force = trigger != "PERIODIC_WORK")
        return if (snapshot?.error == null || snapshot?.error == "Groww credentials are not configured") {
            Result.success()
        } else {
            Result.retry()
        }
    }

    companion object {
        const val KEY_TRIGGER = "recovery_trigger"
    }
}

object RecoveryScheduler {
    fun enqueueImmediate(context: Context, trigger: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<BrokerRecoveryWorker>()
            .setInputData(workDataOf(BrokerRecoveryWorker.KEY_TRIGGER to trigger))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }

    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<BrokerRecoveryWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private const val UNIQUE_WORK = "ipo_sentinel_broker_truth_recovery"
}
