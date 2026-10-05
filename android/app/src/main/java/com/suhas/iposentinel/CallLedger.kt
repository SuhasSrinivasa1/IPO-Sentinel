package com.suhas.iposentinel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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
    val holdPolicy: String? = null,
    val maxHoldTradingSessions: Int = 1,
    val shadowQuantity: Int = 0,
    val shadowEntryValue: Double = 0.0,
    val shadowPnlRupees: Double = 0.0,
    val shadowSessionPnlRupees: Double = 0.0,
    val shadowSessionPnlDate: String? = null,
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

    fun migrateLegacyResearchRows(): Int {
        val before = load()
        val after = before.filterNot { it.callId.startsWith("research:") }
        if (after.size != before.size) save(after)
        return before.size - after.size
    }

    fun syncResearchPlan(plan: ResearchPlan): List<RecommendationCall> {
        migrateLegacyResearchRows()
        return load()
    }

    fun upsertSignal(candidate: ResearchCandidate, decision: SignalDecision): RecommendationCall {
        val signalInstant = runCatching { Instant.parse(decision.signalAt) }.getOrElse { Instant.now() }
        val signalDate = signalInstant.atZone(IST).toLocalDate()
        val callId = "signal:" + candidate.candidateId + ":" + signalDate
        val existing = load().associateBy { it.callId }.toMutableMap()
        val old = existing[callId]
        val hold = ShadowExecutionPolicy.holdFor(old?.strategyId ?: decision.strategyId)

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
            add("Hold policy " + hold.label)
        }.distinct().take(10)

        val mergedConfirmations = (
            old?.confirmingStrategyIds.orEmpty() +
                decision.confirmingStrategyIds +
                listOfNotNull(old?.strategyId)
            ).filter { it != decision.strategyId }.distinct()

        val entry = old?.entryPrice ?: decision.entryPrice
        val stop = old?.stopLoss ?: decision.stopLoss
        val shadowQty = old?.shadowQuantity?.takeIf { it > 0 }
            ?: sizeShadowPosition(existing.values.filterNot { it.callId == callId }, entry, stop)
        val shadowEntryValue = old?.shadowEntryValue?.takeIf { it > 0.0 }
            ?: shadowQty * entry

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
            action = when {
                old?.state == "CLOSED" -> old.action
                shadowQty > 0 -> "ACTIVE SHADOW • " + hold.label
                else -> "SHADOW SIGNAL • CAPITAL FULL"
            },
            recommendedAt = old?.recommendedAt ?: decision.signalAt,
            lastUpdatedAt = Instant.now().toString(),
            sourceGeneratedAt = old?.sourceGeneratedAt ?: decision.signalAt,
            signalScore = max(old?.signalScore ?: 0.0, decision.score),
            entryPrice = entry,
            stopLoss = stop,
            target1 = old?.target1 ?: decision.target1,
            target2 = old?.target2 ?: decision.target2,
            currentPrice = old?.currentPrice ?: entry,
            returnPct = old?.returnPct ?: 0.0,
            evidenceSummary = (old?.evidenceSummary.orEmpty() + evidence).distinct().take(14),
            candlePattern = decision.evidence.candlePattern,
            volumeRatio = decision.evidence.volumeRatio,
            vwap = decision.evidence.vwap,
            benchmarkRelativeBps = decision.evidence.benchmarkRelativeBps,
            holdPolicy = old?.holdPolicy ?: hold.label,
            maxHoldTradingSessions = old?.maxHoldTradingSessions?.takeIf { it > 0 } ?: hold.maxTradingSessions,
            shadowQuantity = shadowQty,
            shadowEntryValue = shadowEntryValue,
            shadowPnlRupees = old?.shadowPnlRupees ?: 0.0,
            shadowSessionPnlRupees = old?.shadowSessionPnlRupees ?: 0.0,
            shadowSessionPnlDate = old?.shadowSessionPnlDate ?: signalDate.toString(),
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
        val ordered = candles.sortedBy { it.timestamp }
        val updated = load().map { call ->
            if (call.state != "LIVE" || !call.symbol.equals(nseSymbol, ignoreCase = true)) {
                return@map call
            }

            val entry = call.entryPrice ?: return@map call
            val signalAt = runCatching { Instant.parse(call.recommendedAt) }.getOrNull()
            val afterSignal = ordered.filter { signalAt == null || it.timestamp.isAfter(signalAt) }
            if (afterSignal.isEmpty()) return@map call

            val maxSessions = call.maxHoldTradingSessions.coerceAtLeast(1)
            val dates = afterSignal.map { it.timestamp.atZone(IST).toLocalDate() }.distinct()
            val allowedDates = dates.take(maxSessions).toSet()
            val relevant = afterSignal.filter { it.timestamp.atZone(IST).toLocalDate() in allowedDates }
            if (relevant.isEmpty()) return@map call

            val latest = relevant.last()
            var current = call.copy(
                currentPrice = latest.close,
                returnPct = (latest.close / entry - 1.0) * 100.0,
                shadowPnlRupees = totalShadowPnl(call, latest.close),
                shadowSessionPnlRupees = sessionShadowPnl(call, relevant, latest.timestamp, latest.close),
                shadowSessionPnlDate = latest.timestamp.atZone(IST).toLocalDate().toString(),
                lastUpdatedAt = now
            )

            val stop = call.stopLoss
            val target = call.target2
            for (candle in relevant) {
                val hitStop = stop != null && candle.low <= stop
                val hitTarget = target != null && candle.high >= target
                when {
                    hitStop && hitTarget -> {
                        current = close(
                            current,
                            candle.timestamp.toString(),
                            stop,
                            "STOP_AND_TARGET_SAME_BAR_STOP_ASSUMED",
                            relevant
                        )
                        break
                    }
                    hitStop -> {
                        current = close(current, candle.timestamp.toString(), stop!!, "STOP", relevant)
                        break
                    }
                    hitTarget -> {
                        current = close(current, candle.timestamp.toString(), target!!, "TARGET2", relevant)
                        break
                    }
                }
            }

            val lastLocal = relevant.last().timestamp.atZone(IST)
            if (
                current.state == "LIVE" &&
                dates.size >= maxSessions &&
                lastLocal.toLocalTime() >= java.time.LocalTime.of(15, 25)
            ) {
                current = close(
                    current,
                    relevant.last().timestamp.toString(),
                    relevant.last().close,
                    if (maxSessions == 1) "SESSION_CLOSE" else "MAX_HOLD_" + maxSessions + "_SESSIONS",
                    relevant
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
                if (mark != null) {
                    updated = updated.copy(
                        state = "CLOSED",
                        action = "CLOSED",
                        closedAt = now,
                        closeReason = "BROKER_POSITION_FLAT",
                        exitPrice = mark,
                        currentPrice = mark,
                        returnPct = if (call.entryPrice != null && call.entryPrice > 0.0) {
                            (mark / call.entryPrice - 1.0) * 100.0
                        } else call.returnPct,
                        shadowPnlRupees = totalShadowPnl(call, mark),
                        lastUpdatedAt = now
                    )
                }
            }
            updated
        }
        save(calls)
        return calls
    }

    private fun sizeShadowPosition(
        calls: Collection<RecommendationCall>,
        entry: Double,
        stop: Double
    ): Int {
        if (entry <= 0.0) return 0
        val allocated = calls
            .filter { it.state == "LIVE" }
            .sumOf { it.shadowEntryValue.coerceAtLeast(0.0) }
        val available = (ShadowExecutionPolicy.ACCOUNT_CAPITAL_RUPEES - allocated).coerceAtLeast(0.0)
        val maxAllocation = min(ShadowExecutionPolicy.MAX_POSITION_RUPEES, available)
        if (maxAllocation < entry) return 0

        val riskPerShare = max(0.01, entry - stop)
        val byRisk = floor(ShadowExecutionPolicy.RISK_PER_TRADE_RUPEES / riskPerShare).toInt()
        val byCapital = floor(maxAllocation / entry).toInt()
        return min(byRisk, byCapital).coerceAtLeast(0)
    }

    private fun totalShadowPnl(call: RecommendationCall, mark: Double): Double {
        val entry = call.entryPrice ?: return 0.0
        return (mark - entry) * call.shadowQuantity
    }

    private fun sessionShadowPnl(
        call: RecommendationCall,
        candles: List<MarketCandle>,
        markAt: Instant,
        mark: Double
    ): Double {
        if (call.shadowQuantity <= 0) return 0.0
        val signalDate = runCatching { Instant.parse(call.recommendedAt).atZone(IST).toLocalDate() }.getOrNull()
        val markDate = markAt.atZone(IST).toLocalDate()
        val entry = call.entryPrice ?: return 0.0
        val reference = if (signalDate == markDate) {
            entry
        } else {
            candles
                .filter { it.timestamp.atZone(IST).toLocalDate().isBefore(markDate) }
                .lastOrNull()
                ?.close
                ?: entry
        }
        return (mark - reference) * call.shadowQuantity
    }

    private fun close(
        call: RecommendationCall,
        at: String,
        exitPrice: Double,
        reason: String,
        candles: List<MarketCandle>
    ): RecommendationCall {
        val entry = call.entryPrice
        val atInstant = runCatching { Instant.parse(at) }.getOrElse { Instant.now() }
        return call.copy(
            state = "CLOSED",
            action = "CLOSED",
            closedAt = at,
            closeReason = reason,
            exitPrice = exitPrice,
            currentPrice = exitPrice,
            returnPct = if (entry != null && entry > 0.0) (exitPrice / entry - 1.0) * 100.0 else null,
            shadowPnlRupees = totalShadowPnl(call, exitPrice),
            shadowSessionPnlRupees = sessionShadowPnl(call, candles, atInstant, exitPrice),
            shadowSessionPnlDate = atInstant.atZone(IST).toLocalDate().toString(),
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
            holdPolicy = obj.optString("hold_policy").ifBlank { null },
            maxHoldTradingSessions = obj.optInt("max_hold_trading_sessions", 1).coerceAtLeast(1),
            shadowQuantity = obj.optInt("shadow_quantity", 0).coerceAtLeast(0),
            shadowEntryValue = obj.optDouble("shadow_entry_value", 0.0),
            shadowPnlRupees = obj.optDouble("shadow_pnl_rupees", 0.0),
            shadowSessionPnlRupees = obj.optDouble("shadow_session_pnl_rupees", 0.0),
            shadowSessionPnlDate = obj.optString("shadow_session_pnl_date").ifBlank { null },
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
                    .put("hold_policy", call.holdPolicy)
                    .put("max_hold_trading_sessions", call.maxHoldTradingSessions)
                    .put("shadow_quantity", call.shadowQuantity)
                    .put("shadow_entry_value", call.shadowEntryValue)
                    .put("shadow_pnl_rupees", call.shadowPnlRupees)
                    .put("shadow_session_pnl_rupees", call.shadowSessionPnlRupees)
                    .put("shadow_session_pnl_date", call.shadowSessionPnlDate)
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
