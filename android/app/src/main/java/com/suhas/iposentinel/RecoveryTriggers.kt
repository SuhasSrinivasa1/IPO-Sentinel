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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
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
        OffMarketResearchScheduler.ensureScheduled(context)
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


class OffMarketResearchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val trigger = inputData.getString(KEY_TRIGGER) ?: "OFF_MARKET_RESEARCH"
            val research = DirectResearchClient(applicationContext)
            val (_, plan) = research.refreshPlan(force = true)
            val replay = StrategyLabEngine(applicationContext).refresh(plan ?: research.cachedPlan())
            CallLedgerStore(applicationContext).syncStrategySignals(plan ?: research.cachedPlan(), replay)
            AppAudit.log(
                applicationContext,
                "SHADOW_REPLAY_COMPLETE",
                org.json.JSONObject()
                    .put("trigger", trigger)
                    .put("symbols_scanned", replay.symbolsScanned)
                    .put("candles_stored", replay.candlesStored)
                    .put("replay_trades", replay.replayTrades)
                    .put("live_signals", replay.liveSignals.size)
                    .put("errors", replay.errors.size)
            )
            OffMarketResearchScheduler.ensureScheduled(applicationContext)
            Result.success()
        } catch (error: Exception) {
            AppAudit.log(
                applicationContext,
                "SHADOW_REPLAY_FAILED",
                org.json.JSONObject().put("error", error.message ?: error.javaClass.simpleName)
            )
            OffMarketResearchScheduler.ensureScheduled(applicationContext)
            Result.retry()
        }
    }

    companion object {
        const val KEY_TRIGGER = "off_market_trigger"
    }
}

object OffMarketResearchScheduler {
    private val IST = ZoneId.of("Asia/Kolkata")

    fun ensureScheduled(context: Context) {
        scheduleSlot(context, 8, 35, "PRE_MARKET_RESEARCH")
        scheduleSlot(context, 18, 45, "POST_MARKET_SHADOW_REPLAY")
    }

    private fun scheduleSlot(
        context: Context,
        hour: Int,
        minute: Int,
        trigger: String
    ) {
        val now = ZonedDateTime.now(IST)
        var target = now.toLocalDate().atTime(hour, minute).atZone(IST)
        if (!target.isAfter(now.plusMinutes(2))) target = target.plusDays(1)

        val delayMillis = Duration.between(now, target).toMillis().coerceAtLeast(0)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<OffMarketResearchWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(workDataOf(OffMarketResearchWorker.KEY_TRIGGER to trigger))
            .build()
        val uniqueName = "ipo_sentinel_" + trigger.lowercase() + "_" + target.toLocalDate()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
