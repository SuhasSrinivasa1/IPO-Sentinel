package com.suhas.iposentinel

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class MarketSignalWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val research = DirectResearchClient(applicationContext)
        val plan = research.cachedPlan()
            ?: research.refreshPlan(force = false).second
            ?: return Result.retry()

        val scan = LiveSignalScanner(applicationContext).scan(plan)
        AppAudit.log(
            applicationContext,
            "BACKGROUND_SIGNAL_SCAN",
            org.json.JSONObject()
                .put("verified_symbols", scan.verifiedSymbols)
                .put("evaluated_symbols", scan.evaluatedSymbols)
                .put("signals_found", scan.signalsFound)
                .put("new_calls", scan.newCalls)
                .put("errors", scan.errors.size)
        )
        return if (scan.errors.size >= 10 && scan.evaluatedSymbols == 0) Result.retry() else Result.success()
    }
}

class PreMarketResearchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val research = DirectResearchClient(applicationContext)
        val (result, plan) = research.refreshPlan(force = true)
        if (!result.ok || plan == null) return Result.retry()

        val scan = LiveSignalScanner(applicationContext).scan(plan)
        AppAudit.log(
            applicationContext,
            "PRE_MARKET_RESEARCH",
            org.json.JSONObject()
                .put("candidate_count", plan.candidateCount)
                .put("nse_identity_confirmed", plan.nseIdentityConfirmedCount)
                .put("groww_resolved", plan.growwResolvedCount)
                .put("verified_symbols", scan.verifiedSymbols)
                .put("evaluated_symbols", scan.evaluatedSymbols)
                .put("signals_found", scan.signalsFound)
                .put("errors", scan.errors.size)
        )
        return if (scan.errors.size >= 10 && scan.evaluatedSymbols == 0) Result.retry() else Result.success()
    }
}

class OffMarketResearchWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val research = DirectResearchClient(applicationContext)
        val (result, plan) = research.refreshPlan(force = true)
        if (!result.ok || plan == null) return Result.retry()

        val summary = ShadowReplayEngine(applicationContext).runFull(plan)
        return if (summary.evaluatedSymbols == 0 && summary.errors.isNotEmpty()) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}

object ResearchLearningScheduler {
    fun ensureScheduled(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val marketScan = PeriodicWorkRequestBuilder<MarketSignalWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        workManager.enqueueUniquePeriodicWork(
            MARKET_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            marketScan
        )

        val preMarket = PeriodicWorkRequestBuilder<PreMarketResearchWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayToMinutes(8, 35), TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PREMARKET_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            preMarket
        )

        val offMarket = PeriodicWorkRequestBuilder<OffMarketResearchWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayToMinutes(18, 45), TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        workManager.enqueueUniquePeriodicWork(
            LEARNING_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            offMarket
        )
    }

    private fun delayToMinutes(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now(IST)
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMinutes().coerceAtLeast(1L)
    }

    private val IST = ZoneId.of("Asia/Kolkata")
    private const val MARKET_WORK = "ipo_sentinel_market_signal_scan_v141"
    private const val PREMARKET_WORK = "ipo_sentinel_premarket_research_v141"
    private const val LEARNING_WORK = "ipo_sentinel_offmarket_learning_v141"
}
