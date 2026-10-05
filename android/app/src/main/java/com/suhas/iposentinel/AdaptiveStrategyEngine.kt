package com.suhas.iposentinel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CompositeStrategyDefinition(
    val id: String,
    val name: String,
    val phase: String,
    val description: String,
    val ingredients: List<String>
)

data class SignalFeatureSnapshot(
    val candlePattern: String,
    val volumeRatio: Double,
    val vwap: Double,
    val vwapDistanceBps: Double,
    val atrPct: Double,
    val openingRangeHigh: Double,
    val openingRangeLow: Double,
    val benchmarkRelativeBps: Double?,
    val listingGapPct: Double?,
    val subscriptionMultiple: Double?,
    val tradingDayNumber: Int?,
    val board: String,
    val issuePriceText: String?
)

data class SignalDecision(
    val strategyId: String,
    val strategyName: String,
    val confirmingStrategyIds: List<String>,
    val signalAt: String,
    val score: Double,
    val direction: String,
    val entryPrice: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val evidence: SignalFeatureSnapshot,
    val reasons: List<String>
)

data class ReplayTrade(
    val strategyId: String,
    val candidateId: String,
    val nseSymbol: String,
    val tradeDate: String,
    val signalAt: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val exitReason: String,
    val netBps: Double,
    val mfeBps: Double,
    val maeBps: Double
)

data class MissedMove(
    val nseSymbol: String,
    val tradeDate: String,
    val maxUpsideBps: Double,
    val blockers: List<String>
)

data class ReplayRunSummary(
    val generatedAt: String,
    val evaluatedSymbols: Int,
    val evaluatedSessions: Int,
    val emittedSignals: Int,
    val missedMoves: List<MissedMove>,
    val errors: List<String>
)

object CompositeStrategyCatalog {
    val definitions = listOf(
        CompositeStrategyDefinition(
            id = "listing_momentum_consensus",
            name = "Listing Momentum Consensus",
            phase = "D0_D3",
            description = "Opening-range breakout confirmed by VWAP acceptance, expanding volume and strong candle location.",
            ingredients = listOf("Opening range", "VWAP", "Volume acceleration", "ATR", "Listing context")
        ),
        CompositeStrategyDefinition(
            id = "vwap_reclaim_absorption",
            name = "VWAP Reclaim + Absorption",
            phase = "D0_D10",
            description = "Flush or rejection below VWAP followed by a reclaim with lower-wick absorption and renewed volume.",
            ingredients = listOf("VWAP reclaim", "Lower-wick absorption", "Volume", "Candle structure", "IPO context")
        ),
        CompositeStrategyDefinition(
            id = "breakout_retest_continuation",
            name = "Breakout Retest Continuation",
            phase = "D1_D30",
            description = "Trend remains above VWAP, a pullback holds structure, then price re-expands through the prior bar high.",
            ingredients = listOf("Trend slope", "VWAP", "Retest", "Higher low", "Volume re-expansion")
        ),
        CompositeStrategyDefinition(
            id = "compression_expansion",
            name = "Compression → Expansion",
            phase = "D1_D30",
            description = "Impulse is followed by a tight low-volume pause, then a fresh breakout while price remains above VWAP.",
            ingredients = listOf("Range compression", "Volume dry-up", "Breakout", "ATR", "VWAP")
        ),
        CompositeStrategyDefinition(
            id = "relative_strength_continuation",
            name = "Relative Strength Continuation",
            phase = "D0_D30",
            description = "IPO outperforms NIFTY while holding VWAP, making higher closes and breaking a short-term high on volume.",
            ingredients = listOf("NIFTY relative strength", "VWAP", "Higher closes", "5-bar breakout", "Volume")
        )
    )

    fun definition(id: String): CompositeStrategyDefinition? = definitions.firstOrNull { it.id == id }
}

data class ShadowHoldPolicy(
    val label: String,
    val maxTradingSessions: Int
)

object ShadowExecutionPolicy {
    const val ACCOUNT_CAPITAL_RUPEES = 100_000.0
    const val MAX_POSITION_RUPEES = 25_000.0
    const val RISK_PER_TRADE_RUPEES = 1_000.0

    fun holdFor(strategyId: String?): ShadowHoldPolicy = when (strategyId) {
        "listing_momentum_consensus" -> ShadowHoldPolicy("INTRADAY", 1)
        "vwap_reclaim_absorption" -> ShadowHoldPolicy("1-2 DAYS", 2)
        "breakout_retest_continuation" -> ShadowHoldPolicy("UP TO 5 DAYS", 5)
        "compression_expansion" -> ShadowHoldPolicy("UP TO 10 DAYS", 10)
        "relative_strength_continuation" -> ShadowHoldPolicy("UP TO 20 DAYS", 20)
        else -> ShadowHoldPolicy("INTRADAY", 1)
    }
}

class AdaptiveStrategyEngine {
    fun evaluateBest(
        candidate: ResearchCandidate,
        stockCandles: List<MarketCandle>,
        benchmarkCandles: List<MarketCandle> = emptyList()
    ): SignalDecision? {
        val all = evaluateAll(candidate, stockCandles, benchmarkCandles)
            .filter { it.score >= MIN_SIGNAL_SCORE }
            .sortedByDescending { it.score }
        val best = all.firstOrNull() ?: return null
        return best.copy(confirmingStrategyIds = all.drop(1).map { it.strategyId })
    }

    fun evaluateAll(
        candidate: ResearchCandidate,
        stockCandles: List<MarketCandle>,
        benchmarkCandles: List<MarketCandle> = emptyList()
    ): List<SignalDecision> {
        if (stockCandles.size < 7) return emptyList()
        val i = stockCandles.lastIndex
        val current = stockCandles[i]
        val vwap = vwap(stockCandles, i)
        val prevVwap = vwap(stockCandles, i - 1)
        val atr = atr(stockCandles, i)
        if (vwap <= 0.0 || atr <= 0.0) return emptyList()

        val opening = stockCandles.take(min(3, stockCandles.size))
        val orHigh = opening.maxOf { it.high }
        val orLow = opening.minOf { it.low }
        val volumeRatio = volumeRatio(stockCandles, i)
        val closeLocation = closeLocation(current)
        val slope = slope(stockCandles, i, 5)
        val pattern = candlePattern(stockCandles, i)
        val benchmarkRelative = relativeStrengthBps(stockCandles, benchmarkCandles)
        val issuePrice = parseIssuePrice(candidate.issuePriceText)
        val listingGapPct = issuePrice?.takeIf { it > 0.0 }?.let {
            (stockCandles.first().open / it - 1.0) * 100.0
        }

        val feature = SignalFeatureSnapshot(
            candlePattern = pattern,
            volumeRatio = volumeRatio,
            vwap = vwap,
            vwapDistanceBps = ((current.close / vwap) - 1.0) * 10_000.0,
            atrPct = (atr / current.close) * 100.0,
            openingRangeHigh = orHigh,
            openingRangeLow = orLow,
            benchmarkRelativeBps = benchmarkRelative,
            listingGapPct = listingGapPct,
            subscriptionMultiple = candidate.subscriptionMultiple,
            tradingDayNumber = candidate.tradingDayNumber,
            board = if (candidate.isSme) "SME" else "MAINBOARD",
            issuePriceText = candidate.issuePriceText
        )

        val out = mutableListOf<SignalDecision>()

        if (
            i >= 3 &&
            current.close > orHigh &&
            current.close > vwap &&
            volumeRatio >= threshold(candidate, 1.35, 1.55) &&
            closeLocation >= 0.64 &&
            slope > 0.0
        ) {
            out += decision(
                "listing_momentum_consensus",
                candidate,
                current,
                atr,
                feature,
                score = 72.0 +
                    min(10.0, (volumeRatio - 1.0) * 8.0) +
                    min(8.0, max(0.0, feature.vwapDistanceBps) / 40.0) +
                    contextBonus(candidate),
                reasons = listOf(
                    "15m opening range cleared",
                    "Price accepted above VWAP",
                    "Volume expanded " + fmt(volumeRatio) + "×",
                    "Strong close location " + fmt(closeLocation * 100.0) + "%"
                )
            )
        }

        val previous = stockCandles[i - 1]
        val prevRange = max(0.0001, previous.high - previous.low)
        val prevLowerWick = min(previous.open, previous.close) - previous.low
        if (
            previous.low < prevVwap &&
            previous.close <= prevVwap * 1.002 &&
            current.close > vwap &&
            current.close > previous.high &&
            prevLowerWick / prevRange >= 0.30 &&
            volumeRatio >= threshold(candidate, 1.10, 1.30)
        ) {
            out += decision(
                "vwap_reclaim_absorption",
                candidate,
                current,
                atr,
                feature,
                score = 73.0 +
                    min(9.0, (prevLowerWick / prevRange) * 12.0) +
                    min(8.0, (volumeRatio - 1.0) * 7.0) +
                    contextBonus(candidate),
                reasons = listOf(
                    "Prior candle rejected below VWAP",
                    "VWAP reclaimed",
                    "Prior high reclaimed",
                    "Absorption wick present",
                    "Volume confirmed"
                )
            )
        }

        if (
            i >= 3 &&
            current.close > vwap &&
            previous.low <= prevVwap * 1.004 &&
            previous.close >= prevVwap * 0.995 &&
            current.close > previous.high &&
            current.low > stockCandles[i - 2].low &&
            volumeRatio >= threshold(candidate, 1.10, 1.28) &&
            slope > 0.0
        ) {
            out += decision(
                "breakout_retest_continuation",
                candidate,
                current,
                atr,
                feature,
                score = 72.0 +
                    min(9.0, max(0.0, feature.vwapDistanceBps) / 35.0) +
                    min(8.0, (volumeRatio - 1.0) * 7.0) +
                    contextBonus(candidate),
                reasons = listOf(
                    "Trend slope positive",
                    "Pullback held VWAP",
                    "Higher low preserved",
                    "Prior high broken",
                    "Volume re-expanded"
                )
            )
        }

        if (i >= 7) {
            val compression = stockCandles.subList(i - 4, i)
            val compressionHigh = compression.maxOf { it.high }
            val compressionLow = compression.minOf { it.low }
            val compressionRange = compressionHigh - compressionLow
            val recentVol = compression.takeLast(3).map { it.volume.toDouble() }.average()
            val priorVol = stockCandles.subList(max(0, i - 9), i - 4)
                .map { it.volume.toDouble() }
                .average()
            val dryUp = priorVol > 0.0 && recentVol <= priorVol * 0.82
            if (
                compressionRange <= atr * 1.55 &&
                dryUp &&
                current.close > compressionHigh &&
                current.close > vwap &&
                volumeRatio >= threshold(candidate, 1.25, 1.45)
            ) {
                out += decision(
                    "compression_expansion",
                    candidate,
                    current,
                    atr,
                    feature,
                    score = 74.0 +
                        min(10.0, (volumeRatio - 1.0) * 8.0) +
                        if (dryUp) 5.0 else 0.0 +
                        contextBonus(candidate),
                    reasons = listOf(
                        "Four-bar range compressed",
                        "Volume dried up during pause",
                        "Compression high broken",
                        "Breakout remains above VWAP",
                        "Volume expanded on release"
                    )
                )
            }
        }

        val prior5High = stockCandles.subList(max(0, i - 5), i).maxOf { it.high }
        if (
            benchmarkRelative != null &&
            benchmarkRelative >= 100.0 &&
            current.close > vwap &&
            current.close > prior5High &&
            volumeRatio >= threshold(candidate, 1.12, 1.30) &&
            slope > 0.0
        ) {
            out += decision(
                "relative_strength_continuation",
                candidate,
                current,
                atr,
                feature,
                score = 72.0 +
                    min(12.0, benchmarkRelative / 80.0) +
                    min(8.0, (volumeRatio - 1.0) * 7.0) +
                    contextBonus(candidate),
                reasons = listOf(
                    "Outperforming NIFTY by " + fmt(benchmarkRelative / 100.0) + "%",
                    "Holding above VWAP",
                    "Five-bar high cleared",
                    "Positive short-term slope",
                    "Volume confirmed"
                )
            )
        }

        return out.map { it.copy(score = it.score.coerceIn(0.0, 99.0)) }
    }

    fun blockers(
        candidate: ResearchCandidate,
        stockCandles: List<MarketCandle>,
        benchmarkCandles: List<MarketCandle>
    ): List<String> {
        if (stockCandles.size < 7) return listOf("Too few 5-minute candles")
        val i = stockCandles.lastIndex
        val current = stockCandles[i]
        val vwap = vwap(stockCandles, i)
        val vr = volumeRatio(stockCandles, i)
        val opening = stockCandles.take(3)
        val out = mutableListOf<String>()
        if (current.close <= vwap) out += "Below VWAP"
        if (vr < threshold(candidate, 1.10, 1.28)) out += "Volume did not expand"
        if (current.close <= opening.maxOf { it.high }) out += "Opening range not cleared"
        relativeStrengthBps(stockCandles, benchmarkCandles)?.let {
            if (it < 100.0) out += "Relative strength vs NIFTY was weak"
        }
        if (slope(stockCandles, i, 5) <= 0.0) out += "Short-term trend was not rising"
        return out.distinct().take(4).ifEmpty { listOf("No composite strategy reached the score threshold") }
    }

    private fun decision(
        id: String,
        candidate: ResearchCandidate,
        candle: MarketCandle,
        atr: Double,
        feature: SignalFeatureSnapshot,
        score: Double,
        reasons: List<String>
    ): SignalDecision {
        val entry = candle.close
        val risk = max(atr * 1.15, entry * 0.006)
        val stop = max(0.01, entry - risk)
        val target1 = entry + risk * 1.5
        val target2 = entry + risk * 2.4
        val def = CompositeStrategyCatalog.definition(id)!!
        return SignalDecision(
            strategyId = id,
            strategyName = def.name,
            confirmingStrategyIds = emptyList(),
            signalAt = candle.timestamp.toString(),
            score = score,
            direction = "BUY",
            entryPrice = entry,
            stopLoss = stop,
            target1 = target1,
            target2 = target2,
            evidence = feature,
            reasons = reasons
        )
    }

    private fun contextBonus(candidate: ResearchCandidate): Double {
        var bonus = if (candidate.isSme) -3.0 else 2.0
        candidate.subscriptionMultiple?.let {
            bonus += when {
                it >= 20.0 -> 5.0
                it >= 5.0 -> 3.0
                it >= 1.0 -> 1.0
                else -> -2.0
            }
        }
        return bonus
    }

    private fun threshold(candidate: ResearchCandidate, mainboard: Double, sme: Double): Double =
        if (candidate.isSme) sme else mainboard

    private fun vwap(candles: List<MarketCandle>, end: Int): Double {
        var pv = 0.0
        var volume = 0.0
        for (i in 0..end.coerceAtMost(candles.lastIndex)) {
            val c = candles[i]
            val v = c.volume.toDouble()
            pv += ((c.high + c.low + c.close) / 3.0) * v
            volume += v
        }
        return if (volume > 0.0) pv / volume else candles[end].close
    }

    private fun atr(candles: List<MarketCandle>, end: Int, length: Int = 14): Double {
        if (end <= 0) return candles[end].high - candles[end].low
        val start = max(1, end - length + 1)
        val values = mutableListOf<Double>()
        for (i in start..end) {
            val c = candles[i]
            val prev = candles[i - 1]
            values += maxOf(
                c.high - c.low,
                abs(c.high - prev.close),
                abs(c.low - prev.close)
            )
        }
        return values.average().takeIf { it.isFinite() && it > 0.0 }
            ?: max(0.01, candles[end].close * 0.01)
    }

    private fun volumeRatio(candles: List<MarketCandle>, end: Int): Double {
        if (end <= 1) return 1.0
        val start = max(0, end - 10)
        val history = candles.subList(start, end).map { it.volume.toDouble() }.filter { it > 0.0 }
        val base = history.average()
        return if (base > 0.0) candles[end].volume.toDouble() / base else 1.0
    }

    private fun slope(candles: List<MarketCandle>, end: Int, length: Int): Double {
        val start = max(0, end - length + 1)
        val slice = candles.subList(start, end + 1)
        if (slice.size < 2) return 0.0
        return slice.last().close - slice.first().close
    }

    private fun closeLocation(c: MarketCandle): Double {
        val range = c.high - c.low
        return if (range <= 0.0) 0.5 else (c.close - c.low) / range
    }

    private fun candlePattern(candles: List<MarketCandle>, i: Int): String {
        val c = candles[i]
        val range = max(0.0001, c.high - c.low)
        val body = abs(c.close - c.open)
        val lower = min(c.open, c.close) - c.low
        val upper = c.high - max(c.open, c.close)
        if (lower >= body * 1.8 && c.close > c.open && upper <= range * 0.25) return "HAMMER"
        if (i > 0) {
            val p = candles[i - 1]
            if (
                c.close > c.open &&
                p.close < p.open &&
                c.open <= p.close &&
                c.close >= p.open
            ) return "BULLISH_ENGULFING"
        }
        if (body / range >= 0.65 && closeLocation(c) >= 0.7) return "STRONG_BULL_CLOSE"
        if (body / range <= 0.15) return "DOJI"
        return "NORMAL"
    }

    private fun relativeStrengthBps(
        stock: List<MarketCandle>,
        benchmark: List<MarketCandle>
    ): Double? {
        if (stock.size < 2 || benchmark.size < 2) return null
        val stockReturn = stock.last().close / stock.first().open - 1.0
        val benchReturn = benchmark.last().close / benchmark.first().open - 1.0
        return (stockReturn - benchReturn) * 10_000.0
    }

    private fun parseIssuePrice(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        return Regex("""\d+(?:\.\d+)?""")
            .findAll(text.replace(",", ""))
            .mapNotNull { it.value.toDoubleOrNull() }
            .maxOrNull()
    }

    private fun fmt(value: Double): String = String.format("%.2f", value)

    companion object {
        private const val MIN_SIGNAL_SCORE = 72.0
    }
}

class StrategyEvidenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadTrades(): List<ReplayTrade> {
        val array = runCatching { JSONArray(prefs.getString(KEY_TRADES, "[]")) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(
                    ReplayTrade(
                        strategyId = o.optString("strategy_id"),
                        candidateId = o.optString("candidate_id"),
                        nseSymbol = o.optString("nse_symbol"),
                        tradeDate = o.optString("trade_date"),
                        signalAt = o.optString("signal_at"),
                        entryPrice = o.optDouble("entry_price"),
                        exitPrice = o.optDouble("exit_price"),
                        exitReason = o.optString("exit_reason"),
                        netBps = o.optDouble("net_bps"),
                        mfeBps = o.optDouble("mfe_bps"),
                        maeBps = o.optDouble("mae_bps")
                    )
                )
            }
        }
    }

    fun saveReplay(trades: List<ReplayTrade>, summary: ReplayRunSummary) {
        val merged = (loadTrades() + trades)
            .distinctBy { it.strategyId + "|" + it.candidateId + "|" + it.tradeDate + "|" + it.signalAt }
            .sortedByDescending { it.tradeDate + it.signalAt }
            .take(MAX_TRADES)

        val array = JSONArray()
        merged.forEach { t ->
            array.put(
                JSONObject()
                    .put("strategy_id", t.strategyId)
                    .put("candidate_id", t.candidateId)
                    .put("nse_symbol", t.nseSymbol)
                    .put("trade_date", t.tradeDate)
                    .put("signal_at", t.signalAt)
                    .put("entry_price", t.entryPrice)
                    .put("exit_price", t.exitPrice)
                    .put("exit_reason", t.exitReason)
                    .put("net_bps", t.netBps)
                    .put("mfe_bps", t.mfeBps)
                    .put("mae_bps", t.maeBps)
            )
        }
        prefs.edit()
            .putString(KEY_TRADES, array.toString())
            .putString(KEY_SUMMARY, summaryToJson(summary).toString())
            .commit()
    }

    fun lastReplay(): ReplayRunSummary? {
        val raw = prefs.getString(KEY_SUMMARY, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            ReplayRunSummary(
                generatedAt = o.optString("generated_at"),
                evaluatedSymbols = o.optInt("evaluated_symbols"),
                evaluatedSessions = o.optInt("evaluated_sessions"),
                emittedSignals = o.optInt("emitted_signals"),
                missedMoves = buildList {
                    val arr = o.optJSONArray("missed_moves") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val blockers = m.optJSONArray("blockers") ?: JSONArray()
                        add(
                            MissedMove(
                                nseSymbol = m.optString("nse_symbol"),
                                tradeDate = m.optString("trade_date"),
                                maxUpsideBps = m.optDouble("max_upside_bps"),
                                blockers = buildList {
                                    for (j in 0 until blockers.length()) add(blockers.optString(j))
                                }
                            )
                        )
                    }
                },
                errors = buildList {
                    val arr = o.optJSONArray("errors") ?: JSONArray()
                    for (i in 0 until arr.length()) add(arr.optString(i))
                }
            )
        }.getOrNull()
    }

    fun summary(): StrategySummary {
        val allTrades = loadTrades()
        val families = CompositeStrategyCatalog.definitions.map { definition ->
            val trades = allTrades
                .filter { it.strategyId == definition.id }
                .sortedBy { it.tradeDate + it.signalAt }
            val wins = trades.filter { it.netBps > 0.0 }
            val losses = trades.filter { it.netBps < 0.0 }
            val winRate = if (trades.isNotEmpty()) wins.size * 100.0 / trades.size else 0.0
            val expectancy = trades.map { it.netBps }.averageOrZero()
            val grossWin = wins.sumOf { it.netBps }
            val grossLoss = abs(losses.sumOf { it.netBps })
            val pf = when {
                trades.isEmpty() -> 0.0
                grossLoss == 0.0 && grossWin > 0.0 -> 9.99
                grossLoss > 0.0 -> grossWin / grossLoss
                else -> 0.0
            }
            val drawdown = maxDrawdownBps(trades.map { it.netBps })
            val last20 = trades.takeLast(20).sumOf { it.netBps }
            val status = when {
                trades.size >= 20 && expectancy >= 35.0 && pf >= 1.25 && drawdown <= 900.0 -> "CHAMPION"
                trades.size >= 10 && expectancy > 0.0 && pf >= 1.05 -> "CHALLENGER"
                else -> "LEARNING"
            }
            val ranking = (
                expectancy * 0.45 +
                    min(250.0, max(0.0, (pf - 1.0) * 100.0)) * 0.25 +
                    winRate * 0.2 -
                    drawdown * 0.05
                ).coerceIn(-100.0, 100.0)

            StrategyFamilyStats(
                familyId = definition.id,
                name = definition.name,
                phase = definition.phase,
                description = definition.description + " • " + definition.ingredients.joinToString(" + "),
                trades = trades.size,
                winRatePct = winRate,
                expectancyBps = expectancy,
                profitFactor = pf,
                maxDrawdownBps = drawdown,
                last20NetBps = last20,
                status = status,
                rankingScore = ranking
            )
        }.sortedByDescending { it.rankingScore }

        return StrategySummary(
            totalStrategyFamilies = families.size,
            testedFamilies = families.count { it.trades > 0 },
            champions = families.count { it.status == "CHAMPION" },
            challengers = families.count { it.status == "CHALLENGER" },
            untestedFamilies = families.count { it.trades == 0 },
            topFive = families.take(5),
            families = families,
            rankingNote = "Five composite strategies are ranked only from persisted shadow-replay evidence. CHAMPION requires at least 20 replay trades, positive expectancy and profit factor ≥ 1.25."
        )
    }

    private fun summaryToJson(summary: ReplayRunSummary): JSONObject {
        val missed = JSONArray()
        summary.missedMoves.forEach { m ->
            missed.put(
                JSONObject()
                    .put("nse_symbol", m.nseSymbol)
                    .put("trade_date", m.tradeDate)
                    .put("max_upside_bps", m.maxUpsideBps)
                    .put("blockers", JSONArray(m.blockers))
            )
        }
        return JSONObject()
            .put("generated_at", summary.generatedAt)
            .put("evaluated_symbols", summary.evaluatedSymbols)
            .put("evaluated_sessions", summary.evaluatedSessions)
            .put("emitted_signals", summary.emittedSignals)
            .put("missed_moves", missed)
            .put("errors", JSONArray(summary.errors))
    }

    private fun maxDrawdownBps(returns: List<Double>): Double {
        var equity = 0.0
        var peak = 0.0
        var maxDd = 0.0
        returns.forEach {
            equity += it
            peak = max(peak, equity)
            maxDd = max(maxDd, peak - equity)
        }
        return maxDd
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    companion object {
        private const val PREFS_NAME = "ipo_sentinel_strategy_evidence_v2"
        private const val KEY_TRADES = "replay_trades"
        private const val KEY_SUMMARY = "last_replay_summary"
        private const val MAX_TRADES = 2500
    }
}
