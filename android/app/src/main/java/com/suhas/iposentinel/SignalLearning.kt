package com.suhas.iposentinel

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

data class SignalScanSummary(
    val scannedAt: String,
    val verifiedSymbols: Int,
    val evaluatedSymbols: Int,
    val signalsFound: Int,
    val newCalls: Int,
    val errors: List<String>
)

class LiveSignalScanner(context: Context) {
    private val appContext = context.applicationContext
    private val market = DirectMarketDataClient(appContext)
    private val engine = AdaptiveStrategyEngine()
    private val ledger = CallLedgerStore(appContext)

    suspend fun scan(plan: ResearchPlan): SignalScanSummary {
        val now = java.time.ZonedDateTime.now(IST)
        val eligible = plan.allKnownCandidates
            .filter(::exactVerifiedIdentity)
            .filter { candidate ->
                val listing = candidate.listingDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                listing != null && !listing.isAfter(now.toLocalDate()) &&
                    ((candidate.tradingDayNumber ?: 0) in 1..30 || listing == now.toLocalDate())
            }
            .distinctBy { it.growwSymbol }
            .sortedBy { it.tradingDayNumber ?: 0 }

        if (!marketOpen(now.toLocalTime()) || now.dayOfWeek.value >= 6) {
            ledger.reconcileExpiredSessionCalls(now.toInstant().toString())
            return SignalScanSummary(
                scannedAt = now.toInstant().toString(),
                verifiedSymbols = eligible.size,
                evaluatedSymbols = 0,
                signalsFound = 0,
                newCalls = 0,
                errors = emptyList()
            )
        }

        val benchmark = market.fetchSessionCandles(
            DirectMarketDataClient.BENCHMARK_GROWW_SYMBOL,
            now.toLocalDate()
        )
        val errors = mutableListOf<String>()
        benchmark.error?.let { errors += "NIFTY:" + it }

        var evaluated = 0
        var signals = 0
        var newCalls = 0
        for (candidate in eligible.take(MAX_LIVE_SYMBOLS)) {
            val growwSymbol = candidate.growwSymbol ?: continue
            val series = market.fetchSessionCandles(growwSymbol, now.toLocalDate())
            if (series.error != null) {
                errors += (candidate.symbol ?: growwSymbol) + ":" + series.error
                continue
            }
            if (series.candles.size < 7) continue
            evaluated += 1

            val beforeIds = ledger.load().filter { it.state == "LIVE" }.map { it.callId }.toSet()
            val decision = engine.evaluateBest(candidate, series.candles, benchmark.candles)
            if (decision != null) {
                signals += 1
                val call = ledger.upsertSignal(candidate, decision)
                if (call.callId !in beforeIds && call.state == "LIVE") {
                    newCalls += 1
                    NotificationHelper.showOrderEvent(
                        appContext,
                        OrderLifecycleEvent(
                            id = System.currentTimeMillis(),
                            timestamp = call.recommendedAt,
                            eventType = "SIGNAL_READY",
                            symbol = call.symbol,
                            side = call.direction,
                            price = call.entryPrice,
                            message = (call.strategyName ?: "Composite strategy") +
                                " • score " + String.format("%.0f", call.signalScore ?: 0.0)
                        )
                    )
                }
            }
            ledger.reconcileMarketCandles(
                nseSymbol = candidate.symbol ?: growwSymbol.substringAfter("NSE-"),
                candles = series.candles
            )
        }

        return SignalScanSummary(
            scannedAt = Instant.now().toString(),
            verifiedSymbols = eligible.size,
            evaluatedSymbols = evaluated,
            signalsFound = signals,
            newCalls = newCalls,
            errors = errors.take(20)
        )
    }

    private fun exactVerifiedIdentity(candidate: ResearchCandidate): Boolean {
        val nse = candidate.symbol?.trim()?.uppercase() ?: return false
        val groww = candidate.growwSymbol?.trim()?.uppercase() ?: return false
        return candidate.nseListingConfirmed &&
            candidate.symbolResolved &&
            candidate.lifecycleState == "READY_FOR_RESEARCH" &&
            groww == "NSE-" + nse
    }

    private fun marketOpen(time: LocalTime): Boolean =
        !time.isBefore(LocalTime.of(9, 15)) && time.isBefore(LocalTime.of(15, 31))

    companion object {
        private val IST = ZoneId.of("Asia/Kolkata")
        private const val MAX_LIVE_SYMBOLS = 30
    }
}

class ShadowReplayEngine(context: Context) {
    private val appContext = context.applicationContext
    private val market = DirectMarketDataClient(appContext)
    private val engine = AdaptiveStrategyEngine()
    private val evidenceStore = StrategyEvidenceStore(appContext)

    suspend fun runFull(plan: ResearchPlan): ReplayRunSummary {
        val today = LocalDate.now(IST)
        val eligible = plan.allKnownCandidates
            .filter(::exactVerifiedIdentity)
            .mapNotNull { candidate ->
                val listing = candidate.listingDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: return@mapNotNull null
                if (listing.isAfter(today)) return@mapNotNull null
                candidate to listing
            }
            .distinctBy { it.first.growwSymbol }
            .sortedByDescending { it.second }
            .take(MAX_REPLAY_SYMBOLS)

        if (eligible.isEmpty()) {
            val summary = ReplayRunSummary(
                generatedAt = Instant.now().toString(),
                evaluatedSymbols = 0,
                evaluatedSessions = 0,
                emittedSignals = 0,
                missedMoves = emptyList(),
                errors = listOf("No exact NSE/Groww identities available for replay")
            )
            evidenceStore.saveReplay(emptyList(), summary)
            return summary
        }

        val earliest = eligible.minOf { it.second }
        val benchmark = market.fetchRange(
            DirectMarketDataClient.BENCHMARK_GROWW_SYMBOL,
            earliest,
            today
        )
        val benchmarkByDate = benchmark.candles.groupBy { it.timestamp.atZone(IST).toLocalDate() }

        val allTrades = mutableListOf<ReplayTrade>()
        val missed = mutableListOf<MissedMove>()
        val errors = mutableListOf<String>()
        benchmark.error?.let { errors += "NIFTY:" + it }
        var sessions = 0
        var evaluatedSymbols = 0

        for ((candidate, listingDate) in eligible) {
            val growwSymbol = candidate.growwSymbol ?: continue
            val endDate = minOf(today, listingDate.plusDays(REPLAY_CALENDAR_DAYS))
            val series = market.fetchRange(growwSymbol, listingDate, endDate)
            if (series.candles.isEmpty()) {
                errors += (candidate.symbol ?: growwSymbol) + ":" + (series.error ?: "No historical candles")
                continue
            }
            evaluatedSymbols += 1

            val sessionsForSymbol = series.candles
                .groupBy { it.timestamp.atZone(IST).toLocalDate() }
                .toSortedMap()
                .entries
                .take(MAX_TRADING_SESSIONS)

            for ((date, candles) in sessionsForSymbol) {
                if (candles.size < 7) continue
                sessions += 1
                val benchmarkDay = benchmarkByDate[date].orEmpty()
                val dayTrades = replaySession(candidate, date, candles, benchmarkDay)
                allTrades += dayTrades

                if (dayTrades.isEmpty()) {
                    val anchor = candles[min(2, candles.lastIndex)].close
                    val maxHigh = candles.drop(min(3, candles.size)).maxOfOrNull { it.high } ?: anchor
                    val upsideBps = if (anchor > 0.0) (maxHigh / anchor - 1.0) * 10_000.0 else 0.0
                    if (upsideBps >= MISSED_MOVE_BPS) {
                        missed += MissedMove(
                            nseSymbol = candidate.symbol ?: growwSymbol.substringAfter("NSE-"),
                            tradeDate = date.toString(),
                            maxUpsideBps = upsideBps,
                            blockers = engine.blockers(candidate, candles, benchmarkDay)
                        )
                    }
                }
            }
        }

        val summary = ReplayRunSummary(
            generatedAt = Instant.now().toString(),
            evaluatedSymbols = evaluatedSymbols,
            evaluatedSessions = sessions,
            emittedSignals = allTrades.size,
            missedMoves = missed.sortedByDescending { it.maxUpsideBps }.take(20),
            errors = errors.distinct().take(30)
        )
        evidenceStore.saveReplay(allTrades, summary)
        AppAudit.log(
            appContext,
            "SHADOW_REPLAY_COMPLETED",
            org.json.JSONObject()
                .put("evaluated_symbols", summary.evaluatedSymbols)
                .put("evaluated_sessions", summary.evaluatedSessions)
                .put("signals", summary.emittedSignals)
                .put("missed_moves", summary.missedMoves.size)
                .put("errors", summary.errors.size)
        )
        return summary
    }

    private fun replaySession(
        candidate: ResearchCandidate,
        date: LocalDate,
        candles: List<MarketCandle>,
        benchmark: List<MarketCandle>
    ): List<ReplayTrade> {
        val emitted = mutableSetOf<String>()
        val trades = mutableListOf<ReplayTrade>()
        for (i in 6 until candles.lastIndex) {
            val prefix = candles.subList(0, i + 1)
            val benchPrefix = benchmark.filter { !it.timestamp.isAfter(candles[i].timestamp) }
            val signals = engine.evaluateAll(candidate, prefix, benchPrefix)
                .filter { it.score >= MIN_REPLAY_SCORE }
            for (signal in signals) {
                if (!emitted.add(signal.strategyId)) continue
                trades += simulate(
                    candidate = candidate,
                    date = date,
                    signal = signal,
                    future = candles.subList(i + 1, candles.size)
                )
            }
            if (emitted.size == CompositeStrategyCatalog.definitions.size) break
        }
        return trades
    }

    private fun simulate(
        candidate: ResearchCandidate,
        date: LocalDate,
        signal: SignalDecision,
        future: List<MarketCandle>
    ): ReplayTrade {
        val entry = signal.entryPrice
        var exit = future.lastOrNull()?.close ?: entry
        var reason = "SESSION_CLOSE"
        var maxHigh = entry
        var minLow = entry

        for (candle in future) {
            maxHigh = max(maxHigh, candle.high)
            minLow = min(minLow, candle.low)
            val hitStop = candle.low <= signal.stopLoss
            val hitTarget = candle.high >= signal.target2
            when {
                hitStop && hitTarget -> {
                    // No intrabar ordering is available. Pessimistic assumption avoids optimistic replay bias.
                    exit = signal.stopLoss
                    reason = "STOP_AND_TARGET_SAME_BAR_STOP_ASSUMED"
                    break
                }
                hitStop -> {
                    exit = signal.stopLoss
                    reason = "STOP"
                    break
                }
                hitTarget -> {
                    exit = signal.target2
                    reason = "TARGET2"
                    break
                }
            }
        }

        return ReplayTrade(
            strategyId = signal.strategyId,
            candidateId = candidate.candidateId,
            nseSymbol = candidate.symbol ?: candidate.growwSymbol.orEmpty().substringAfter("NSE-"),
            tradeDate = date.toString(),
            signalAt = signal.signalAt,
            entryPrice = entry,
            exitPrice = exit,
            exitReason = reason,
            netBps = if (entry > 0.0) (exit / entry - 1.0) * 10_000.0 else 0.0,
            mfeBps = if (entry > 0.0) (maxHigh / entry - 1.0) * 10_000.0 else 0.0,
            maeBps = if (entry > 0.0) (minLow / entry - 1.0) * 10_000.0 else 0.0
        )
    }

    private fun exactVerifiedIdentity(candidate: ResearchCandidate): Boolean {
        val nse = candidate.symbol?.trim()?.uppercase() ?: return false
        val groww = candidate.growwSymbol?.trim()?.uppercase() ?: return false
        return candidate.nseListingConfirmed &&
            candidate.symbolResolved &&
            groww == "NSE-" + nse
    }

    companion object {
        private val IST = ZoneId.of("Asia/Kolkata")
        private const val MAX_REPLAY_SYMBOLS = 30
        private const val MAX_TRADING_SESSIONS = 30
        private const val REPLAY_CALENDAR_DAYS = 45L
        private const val MIN_REPLAY_SCORE = 72.0
        private const val MISSED_MOVE_BPS = 400.0
    }
}
