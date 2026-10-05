package com.suhas.iposentinel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

data class RecommendationCall(
    val callId: String,
    val candidateId: String,
    val symbol: String?,
    val isin: String? = null,
    val growwSymbol: String? = null,
    val companyName: String,
    val board: String,
    val state: String,
    val direction: String = "BUY",
    val strategyId: String? = null,
    val strategyName: String? = null,
    val confirmingStrategyIds: List<String> = emptyList(),
    val action: String,
    val recommendedAt: String,
    val lastUpdatedAt: String,
    val sourceGeneratedAt: String?,
    val signalScore: Double? = null,
    val entryPrice: Double? = null,
    val stopLoss: Double? = null,
    val target1: Double? = null,
    val target2: Double? = null,
    val currentPrice: Double? = null,
    val returnPct: Double? = null,
    val evidenceSummary: List<String> = emptyList(),
    val candlePattern: String? = null,
    val volumeRatio: Double? = null,
    val vwap: Double? = null,
    val benchmarkRelativeBps: Double? = null,
    val closedAt: String? = null,
    val closeReason: String? = null,
    val exitPrice: Double? = null,
    val brokerOrderId: String? = null,
    val brokerOrderStatus: String? = null,
    val brokerPositionQuantity: Int? = null,
    val brokerAveragePrice: Double? = null,
    val lastBrokerReconciledAt: String? = null
)

class CallLedgerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<RecommendationCall> {
        val raw = prefs.getString(KEY_CALLS, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(parse(obj))
            }
        }.sortedWith(
            compareByDescending<RecommendationCall> { it.state == "LIVE" }
                .thenByDescending { it.recommendedAt }
        )
    }

    /**
     * v1.3.3 incorrectly surfaced READY_FOR_RESEARCH rows as live calls.
     * v1.4.0 removes those legacy rows. A LIVE call must now come from actual
     * timestamped Groww candle evidence through AdaptiveStrategyEngine.
     */
    fun migrateLegacyResearchRows(): Int {
        val before = load()
        val after = before.filterNot { it.callId.startsWith("research:") }
        if (after.size != before.size) save(after)
        return before.size - after.size
    }

    fun syncResearchPlan(plan: ResearchPlan): List<RecommendationCall> {
        // Deliberately no-op for call creation. Research candidates belong in Research,
        // not in the Calls ledger.
        migrateLegacyResearchRows()
        return load()
    }

    fun upsertSignal(candidate: ResearchCandidate, decision: SignalDecision): RecommendationCall {
        val signalInstant = runCatching { Instant.parse(decision.signalAt) }.getOrElse { Instant.now() }
        val date = signalInstant.atZone(IST).toLocalDate()
        val callId = "signal:" + candidate.candidateId + ":" + date
        val existing = load().associateBy { it.callId }.toMutableMap()
        val old = existing[callId]

        val evidence = buildList {
            addAll(decision.reasons)
            add("Pattern " + decision.evidence.candlePattern)
            add("Volume " + String.format("%.2f", decision.evidence.volumeRatio) + "×")
            add("VWAP distance " + String.format("%.0f", decision.evidence.vwapDistanceBps) + " bps")
            decision.evidence.benchmarkRelativeBps?.let {
                add("Relative to NIFTY " + String.format("%.0f", it) + " bps")
            }
            decision.evidence.subscriptionMultiple?.let {
                add("IPO subscription " + String.format("%.1f", it) + "×")
            }
        }.distinct().take(9)

        val mergedConfirmations = (
            old?.confirmingStrategyIds.orEmpty() +
                decision.confirmingStrategyIds +
                listOfNotNull(old?.strategyId)
            ).filter { it != decision.strategyId }.distinct()

        val call = RecommendationCall(
            callId = callId,
            candidateId = candidate.candidateId,
            symbol = candidate.symbol,
            isin = candidate.isin,
            growwSymbol = candidate.growwSymbol,
            companyName = candidate.companyName,
            board = if (candidate.isSme) "SME" else "MAINBOARD",
            state = old?.state ?: "LIVE",
            direction = decision.direction,
            strategyId = old?.strategyId ?: decision.strategyId,
            strategyName = old?.strategyName ?: decision.strategyName,
            confirmingStrategyIds = mergedConfirmations,
            action = if (old?.state == "CLOSED") old.action else "ACTIVE SHADOW CALL",
            recommendedAt = old?.recommendedAt ?: decision.signalAt,
            lastUpdatedAt = Instant.now().toString(),
            sourceGeneratedAt = old?.sourceGeneratedAt ?: decision.signalAt,
            signalScore = max(old?.signalScore ?: 0.0, decision.score),
            entryPrice = old?.entryPrice ?: decision.entryPrice,
            stopLoss = old?.stopLoss ?: decision.stopLoss,
            target1 = old?.target1 ?: decision.target1,
            target2 = old?.target2 ?: decision.target2,
            currentPrice = old?.currentPrice ?: decision.entryPrice,
            returnPct = old?.returnPct,
            evidenceSummary = (old?.evidenceSummary.orEmpty() + evidence).distinct().take(12),
            candlePattern = decision.evidence.candlePattern,
            volumeRatio = decision.evidence.volumeRatio,
            vwap = decision.evidence.vwap,
            benchmarkRelativeBps = decision.evidence.benchmarkRelativeBps,
            closedAt = old?.closedAt,
            closeReason = old?.closeReason,
            exitPrice = old?.exitPrice,
            brokerOrderId = old?.brokerOrderId,
            brokerOrderStatus = old?.brokerOrderStatus,
            brokerPositionQuantity = old?.brokerPositionQuantity,
            brokerAveragePrice = old?.brokerAveragePrice,
            lastBrokerReconciledAt = old?.lastBrokerReconciledAt
        )
        existing[callId] = call
        save(existing.values.toList())
        return call
    }

    fun reconcileMarketCandles(nseSymbol: String, candles: List<MarketCandle>): List<RecommendationCall> {
        if (candles.isEmpty()) return load()
        val now = Instant.now().toString()
        val updated = load().map { call ->
            if (call.state != "LIVE" || !call.symbol.equals(nseSymbol, ignoreCase = true)) {
                return@map call
            }
            val entry = call.entryPrice ?: return@map call
            val signalAt = runCatching { Instant.parse(call.recommendedAt) }.getOrNull()
            val afterSignal = candles.filter { signalAt == null || it.timestamp.isAfter(signalAt) }
            if (afterSignal.isEmpty()) return@map call

            var current = call.copy(
                currentPrice = afterSignal.last().close,
                returnPct = (afterSignal.last().close / entry - 1.0) * 100.0,
                lastUpdatedAt = now
            )

            val stop = call.stopLoss
            val target = call.target2
            for (candle in afterSignal) {
                val hitStop = stop != null && candle.low <= stop
                val hitTarget = target != null && candle.high >= target
                when {
                    hitStop && hitTarget -> {
                        current = close(
                            current,
                            candle.timestamp.toString(),
                            stop,
                            "STOP_AND_TARGET_SAME_BAR_STOP_ASSUMED"
                        )
                        break
                    }
                    hitStop -> {
                        current = close(current, candle.timestamp.toString(), stop!!, "STOP")
                        break
                    }
                    hitTarget -> {
                        current = close(current, candle.timestamp.toString(), target!!, "TARGET2")
                        break
                    }
                }
            }

            val lastLocal = afterSignal.last().timestamp.atZone(IST)
            if (
                current.state == "LIVE" &&
                lastLocal.toLocalTime() >= java.time.LocalTime.of(15, 25)
            ) {
                current = close(
                    current,
                    afterSignal.last().timestamp.toString(),
                    afterSignal.last().close,
                    "SESSION_CLOSE"
                )
            }
            current
        }
        save(updated)
        return updated
    }

    fun reconcileExpiredSessionCalls(nowIso: String): List<RecommendationCall> {
        val now = runCatching { Instant.parse(nowIso).atZone(IST) }.getOrElse { Instant.now().atZone(IST) }
        if (now.toLocalTime() < java.time.LocalTime.of(15, 31)) return load()
        // Calls without a post-close candle remain LIVE until a market-data refresh/replay
        // supplies an actual close. Never invent an exit price.
        return load()
    }

    fun reconcileBroker(snapshot: BrokerTruthSnapshot): List<RecommendationCall> {
        val now = snapshot.fetchedAt
        val calls = load().map { call ->
            val symbol = call.symbol?.uppercase() ?: return@map call
            val matchingOrders = snapshot.orders.filter { it.tradingSymbol.uppercase() == symbol }
            val latest = matchingOrders.maxByOrNull { it.createdAt ?: "" }
            val position = snapshot.positions.firstOrNull { it.tradingSymbol.uppercase() == symbol }
            val priorQty = call.brokerPositionQuantity ?: 0
            val newQty = position?.netQuantity ?: 0

            var updated = call.copy(
                brokerOrderId = latest?.growwOrderId ?: call.brokerOrderId,
                brokerOrderStatus = latest?.orderStatus ?: call.brokerOrderStatus,
                brokerPositionQuantity = position?.netQuantity,
                brokerAveragePrice = position?.averagePrice,
                lastBrokerReconciledAt = now,
                lastUpdatedAt = now
            )

            if (call.state == "LIVE" && priorQty != 0 && newQty == 0) {
                val mark = call.currentPrice ?: call.entryPrice
                updated = updated.copy(
                    state = "CLOSED",
                    closedAt = now,
                    closeReason = "BROKER_POSITION_FLAT",
                    exitPrice = mark,
                    returnPct = if (mark != null && call.entryPrice != null && call.entryPrice > 0.0) {
                        (mark / call.entryPrice - 1.0) * 100.0
                    } else call.returnPct
                )
            }
            updated
        }
        save(calls)
        return calls
    }

    private fun close(
        call: RecommendationCall,
        at: String,
        exitPrice: Double,
        reason: String
    ): RecommendationCall {
        val entry = call.entryPrice
        return call.copy(
            state = "CLOSED",
            action = "CLOSED",
            closedAt = at,
            closeReason = reason,
            exitPrice = exitPrice,
            currentPrice = exitPrice,
            returnPct = if (entry != null && entry > 0.0) (exitPrice / entry - 1.0) * 100.0 else null,
            lastUpdatedAt = at
        )
    }

    private fun parse(obj: JSONObject): RecommendationCall {
        fun stringList(name: String): List<String> {
            val arr = obj.optJSONArray(name) ?: return emptyList()
            return buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
        }
        return RecommendationCall(
            callId = obj.optString("call_id"),
            candidateId = obj.optString("candidate_id"),
            symbol = obj.optString("symbol").ifBlank { null },
            isin = obj.optString("isin").ifBlank { null },
            growwSymbol = obj.optString("groww_symbol").ifBlank { null },
            companyName = obj.optString("company_name"),
            board = obj.optString("board", "UNKNOWN"),
            state = obj.optString("state", "LIVE"),
            direction = obj.optString("direction", "BUY"),
            strategyId = obj.optString("strategy_id").ifBlank { null },
            strategyName = obj.optString("strategy_name").ifBlank { null },
            confirmingStrategyIds = stringList("confirming_strategy_ids"),
            action = obj.optString("action", "WAIT LIVE CONFIRMATION"),
            recommendedAt = obj.optString("recommended_at"),
            lastUpdatedAt = obj.optString("last_updated_at"),
            sourceGeneratedAt = obj.optString("source_generated_at").ifBlank { null },
            signalScore = nullableDouble(obj, "signal_score"),
            entryPrice = nullableDouble(obj, "entry_price"),
            stopLoss = nullableDouble(obj, "stop_loss"),
            target1 = nullableDouble(obj, "target1"),
            target2 = nullableDouble(obj, "target2"),
            currentPrice = nullableDouble(obj, "current_price"),
            returnPct = nullableDouble(obj, "return_pct"),
            evidenceSummary = stringList("evidence_summary"),
            candlePattern = obj.optString("candle_pattern").ifBlank { null },
            volumeRatio = nullableDouble(obj, "volume_ratio"),
            vwap = nullableDouble(obj, "vwap"),
            benchmarkRelativeBps = nullableDouble(obj, "benchmark_relative_bps"),
            closedAt = obj.optString("closed_at").ifBlank { null },
            closeReason = obj.optString("close_reason").ifBlank { null },
            exitPrice = nullableDouble(obj, "exit_price"),
            brokerOrderId = obj.optString("broker_order_id").ifBlank { null },
            brokerOrderStatus = obj.optString("broker_order_status").ifBlank { null },
            brokerPositionQuantity = if (obj.isNull("broker_position_quantity")) null else obj.optInt("broker_position_quantity"),
            brokerAveragePrice = nullableDouble(obj, "broker_average_price"),
            lastBrokerReconciledAt = obj.optString("last_broker_reconciled_at").ifBlank { null }
        )
    }

    private fun save(calls: List<RecommendationCall>) {
        val sorted = calls
            .sortedWith(compareByDescending<RecommendationCall> { it.state == "LIVE" }.thenByDescending { it.recommendedAt })
            .take(MAX_CALL_HISTORY)
        val array = JSONArray()
        sorted.forEach { call ->
            array.put(
                JSONObject()
                    .put("call_id", call.callId)
                    .put("candidate_id", call.candidateId)
                    .put("symbol", call.symbol)
                    .put("isin", call.isin)
                    .put("groww_symbol", call.growwSymbol)
                    .put("company_name", call.companyName)
                    .put("board", call.board)
                    .put("state", call.state)
                    .put("direction", call.direction)
                    .put("strategy_id", call.strategyId)
                    .put("strategy_name", call.strategyName)
                    .put("confirming_strategy_ids", JSONArray(call.confirmingStrategyIds))
                    .put("action", call.action)
                    .put("recommended_at", call.recommendedAt)
                    .put("last_updated_at", call.lastUpdatedAt)
                    .put("source_generated_at", call.sourceGeneratedAt)
                    .put("signal_score", call.signalScore)
                    .put("entry_price", call.entryPrice)
                    .put("stop_loss", call.stopLoss)
                    .put("target1", call.target1)
                    .put("target2", call.target2)
                    .put("current_price", call.currentPrice)
                    .put("return_pct", call.returnPct)
                    .put("evidence_summary", JSONArray(call.evidenceSummary))
                    .put("candle_pattern", call.candlePattern)
                    .put("volume_ratio", call.volumeRatio)
                    .put("vwap", call.vwap)
                    .put("benchmark_relative_bps", call.benchmarkRelativeBps)
                    .put("closed_at", call.closedAt)
                    .put("close_reason", call.closeReason)
                    .put("exit_price", call.exitPrice)
                    .put("broker_order_id", call.brokerOrderId)
                    .put("broker_order_status", call.brokerOrderStatus)
                    .put("broker_position_quantity", call.brokerPositionQuantity)
                    .put("broker_average_price", call.brokerAveragePrice)
                    .put("last_broker_reconciled_at", call.lastBrokerReconciledAt)
            )
        }
        prefs.edit().putString(KEY_CALLS, array.toString()).commit()
    }

    private fun nullableDouble(obj: JSONObject, name: String): Double? =
        if (obj.has(name) && !obj.isNull(name)) obj.optDouble(name) else null

    companion object {
        private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        private const val PREFS_NAME = "ipo_sentinel_call_ledger"
        private const val KEY_CALLS = "calls_json"
        private const val MAX_CALL_HISTORY = 500
    }
}
