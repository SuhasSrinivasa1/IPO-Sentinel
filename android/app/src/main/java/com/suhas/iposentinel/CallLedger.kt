package com.suhas.iposentinel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

data class RecommendationCall(
    val callId: String,
    val candidateId: String,
    val symbol: String?,
    val companyName: String,
    val board: String,
    val state: String,
    val action: String,
    val recommendedAt: String,
    val lastUpdatedAt: String,
    val sourceGeneratedAt: String?,
    val strategyId: String? = null,
    val strategyName: String? = null,
    val setupScore: Double? = null,
    val signalAt: String? = null,
    val referencePrice: Double? = null,
    val vwap: Double? = null,
    val relativeVolume: Double? = null,
    val reasonCodes: List<String> = emptyList(),
    val closedAt: String? = null,
    val closeReason: String? = null,
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
                val reasons = obj.optJSONArray("reason_codes") ?: JSONArray()
                add(
                    RecommendationCall(
                        callId = obj.optString("call_id"),
                        candidateId = obj.optString("candidate_id"),
                        symbol = obj.optString("symbol").ifBlank { null },
                        companyName = obj.optString("company_name"),
                        board = obj.optString("board", "UNKNOWN"),
                        state = obj.optString("state", "LIVE"),
                        action = obj.optString("action", "WAIT LIVE CONFIRMATION"),
                        recommendedAt = obj.optString("recommended_at"),
                        lastUpdatedAt = obj.optString("last_updated_at"),
                        sourceGeneratedAt = obj.optString("source_generated_at").ifBlank { null },
                        strategyId = obj.optString("strategy_id").ifBlank { null },
                        strategyName = obj.optString("strategy_name").ifBlank { null },
                        setupScore = if (obj.isNull("setup_score")) null else obj.optDouble("setup_score"),
                        signalAt = obj.optString("signal_at").ifBlank { null },
                        referencePrice = if (obj.isNull("reference_price")) null else obj.optDouble("reference_price"),
                        vwap = if (obj.isNull("vwap")) null else obj.optDouble("vwap"),
                        relativeVolume = if (obj.isNull("relative_volume")) null else obj.optDouble("relative_volume"),
                        reasonCodes = buildList {
                            for (j in 0 until reasons.length()) {
                                reasons.optString(j).takeIf { it.isNotBlank() }?.let(::add)
                            }
                        },
                        closedAt = obj.optString("closed_at").ifBlank { null },
                        closeReason = obj.optString("close_reason").ifBlank { null },
                        brokerOrderId = obj.optString("broker_order_id").ifBlank { null },
                        brokerOrderStatus = obj.optString("broker_order_status").ifBlank { null },
                        brokerPositionQuantity = if (obj.isNull("broker_position_quantity")) null else obj.optInt("broker_position_quantity"),
                        brokerAveragePrice = if (obj.isNull("broker_average_price")) null else obj.optDouble("broker_average_price"),
                        lastBrokerReconciledAt = obj.optString("last_broker_reconciled_at").ifBlank { null }
                    )
                )
            }
        }.sortedWith(
            compareByDescending<RecommendationCall> { it.state == "LIVE" }
                .thenByDescending { it.lastUpdatedAt }
        )
    }

    /**
     * v1.4 call semantics:
     * a research candidate is NOT a call. A call exists only after the exact NSE/Groww
     * identity receives a strategy-qualified market-data signal.
     */
    fun syncStrategySignals(
        plan: ResearchPlan?,
        replay: ShadowReplaySummary
    ): List<RecommendationCall> {
        val now = Instant.now()
        val nowText = now.toString()
        val existing = load().associateBy { it.callId }.toMutableMap()
        val candidateMap = plan?.allKnownCandidates.orEmpty().associateBy { it.candidateId }
        val activeIds = mutableSetOf<String>()

        replay.liveSignals
            .filter { it.dataFresh }
            .forEach { signal ->
                val candidate = candidateMap[signal.candidateId] ?: return@forEach
                if (!candidate.symbolResolved || candidate.growwSymbol.isNullOrBlank()) return@forEach

                val callId = "signal:" + signal.candidateId + ":" + signal.strategyId
                activeIds += callId
                val old = existing[callId]
                existing[callId] = RecommendationCall(
                    callId = callId,
                    candidateId = signal.candidateId,
                    symbol = signal.symbol.uppercase(),
                    companyName = signal.companyName,
                    board = signal.board,
                    state = "LIVE",
                    action = "WATCH LONG",
                    recommendedAt = old?.recommendedAt ?: nowText,
                    lastUpdatedAt = nowText,
                    sourceGeneratedAt = plan?.generatedAt,
                    strategyId = signal.strategyId,
                    strategyName = signal.strategyName,
                    setupScore = signal.score,
                    signalAt = signal.signalAt,
                    referencePrice = signal.referencePrice,
                    vwap = signal.vwap,
                    relativeVolume = signal.relativeVolume,
                    reasonCodes = signal.reasonCodes,
                    closedAt = null,
                    closeReason = null,
                    brokerOrderId = old?.brokerOrderId,
                    brokerOrderStatus = old?.brokerOrderStatus,
                    brokerPositionQuantity = old?.brokerPositionQuantity,
                    brokerAveragePrice = old?.brokerAveragePrice,
                    lastBrokerReconciledAt = old?.lastBrokerReconciledAt
                )
            }

        existing.values
            .filter { it.state == "LIVE" && it.callId.startsWith("research:") }
            .forEach { old ->
                existing[old.callId] = old.copy(
                    state = "CLOSED",
                    lastUpdatedAt = nowText,
                    closedAt = nowText,
                    closeReason = "MIGRATED_TO_STRATEGY_QUALIFIED_CALLS"
                )
            }

        existing.values
            .filter { it.state == "LIVE" && it.callId.startsWith("signal:") && it.callId !in activeIds }
            .forEach { old ->
                val positionOpen = (old.brokerPositionQuantity ?: 0) != 0
                val last = runCatching { Instant.parse(old.lastUpdatedAt) }.getOrNull()
                val expired = last == null || Duration.between(last, now).toMinutes() >= SIGNAL_EXPIRY_MINUTES
                if (!positionOpen && expired) {
                    existing[old.callId] = old.copy(
                        state = "CLOSED",
                        lastUpdatedAt = nowText,
                        closedAt = nowText,
                        closeReason = "SETUP_NO_LONGER_ACTIVE"
                    )
                }
            }

        val merged = existing.values
            .sortedWith(
                compareByDescending<RecommendationCall> { it.state == "LIVE" }
                    .thenByDescending { it.recommendedAt }
            )
            .take(MAX_CALL_HISTORY)
        save(merged)
        return merged
    }

    fun reconcileBroker(snapshot: BrokerTruthSnapshot): List<RecommendationCall> {
        val now = snapshot.fetchedAt
        val calls = load().map { call ->
            val symbol = call.symbol?.uppercase()?.removePrefix("NSE-") ?: return@map call
            val matchingOrders = snapshot.orders.filter {
                it.tradingSymbol.uppercase().removePrefix("NSE-") == symbol
            }
            val latest = matchingOrders.maxByOrNull { it.createdAt ?: "" }
            val position = snapshot.positions.firstOrNull {
                it.tradingSymbol.uppercase().removePrefix("NSE-") == symbol
            }
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
                updated = updated.copy(
                    state = "CLOSED",
                    closedAt = now,
                    closeReason = "BROKER_POSITION_FLAT"
                )
            }
            updated
        }
        save(calls)
        return calls
    }

    private fun save(calls: List<RecommendationCall>) {
        val array = JSONArray()
        calls.forEach { call ->
            array.put(
                JSONObject()
                    .put("call_id", call.callId)
                    .put("candidate_id", call.candidateId)
                    .put("symbol", call.symbol)
                    .put("company_name", call.companyName)
                    .put("board", call.board)
                    .put("state", call.state)
                    .put("action", call.action)
                    .put("recommended_at", call.recommendedAt)
                    .put("last_updated_at", call.lastUpdatedAt)
                    .put("source_generated_at", call.sourceGeneratedAt)
                    .put("strategy_id", call.strategyId)
                    .put("strategy_name", call.strategyName)
                    .put("setup_score", call.setupScore)
                    .put("signal_at", call.signalAt)
                    .put("reference_price", call.referencePrice)
                    .put("vwap", call.vwap)
                    .put("relative_volume", call.relativeVolume)
                    .put("reason_codes", JSONArray(call.reasonCodes))
                    .put("closed_at", call.closedAt)
                    .put("close_reason", call.closeReason)
                    .put("broker_order_id", call.brokerOrderId)
                    .put("broker_order_status", call.brokerOrderStatus)
                    .put("broker_position_quantity", call.brokerPositionQuantity)
                    .put("broker_average_price", call.brokerAveragePrice)
                    .put("last_broker_reconciled_at", call.lastBrokerReconciledAt)
            )
        }
        prefs.edit().putString(KEY_CALLS, array.toString()).commit()
    }

    companion object {
        private const val PREFS_NAME = "ipo_sentinel_call_ledger"
        private const val KEY_CALLS = "calls_json"
        private const val MAX_CALL_HISTORY = 300
        private const val SIGNAL_EXPIRY_MINUTES = 45L
    }
}
