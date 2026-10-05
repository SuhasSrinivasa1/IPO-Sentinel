package com.suhas.iposentinel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
        }.sortedByDescending { it.lastUpdatedAt }
    }

    fun syncResearchPlan(plan: ResearchPlan): List<RecommendationCall> {
        val now = Instant.now().toString()
        val existing = load().associateBy { it.callId }.toMutableMap()
        val eligible = (plan.nextTradingDayCandidates + plan.allKnownCandidates.filter {
            (it.tradingDayNumber ?: 0) in 1..30
        })
            .distinctBy { it.candidateId }
            .filter { it.symbolResolved && it.lifecycleState == "READY_FOR_RESEARCH" }
            .sortedWith(
                compareBy<ResearchCandidate> { it.tradingDayNumber ?: -1 }
                    .thenBy { it.listingDate ?: "" }
                    .thenBy { it.symbol ?: it.companyName }
            )

        val activeIds = mutableSetOf<String>()
        eligible.forEach { candidate ->
            val callId = "research:" + candidate.candidateId
            activeIds += callId
            val old = existing[callId]
            existing[callId] = RecommendationCall(
                callId = callId,
                candidateId = candidate.candidateId,
                symbol = candidate.growwSymbol ?: candidate.symbol,
                companyName = candidate.companyName,
                board = if (candidate.isSme) "SME" else "MAINBOARD",
                state = "LIVE",
                action = candidate.resolutionStatus
                    .replace("_", " ")
                    .ifBlank { "WAIT LIVE CONFIRMATION" },
                recommendedAt = old?.recommendedAt ?: now,
                lastUpdatedAt = now,
                sourceGeneratedAt = plan.generatedAt,
                closedAt = null,
                closeReason = null,
                brokerOrderId = old?.brokerOrderId,
                brokerOrderStatus = old?.brokerOrderStatus,
                brokerPositionQuantity = old?.brokerPositionQuantity,
                brokerAveragePrice = old?.brokerAveragePrice,
                lastBrokerReconciledAt = old?.lastBrokerReconciledAt
            )
        }

        // Only retire missing calls from a fully healthy fresh research cycle.
        // A degraded/cached NSE cycle must not manufacture a "closed" recommendation.
        if (plan.researchHealth == "OK" && plan.sourceReady) {
            existing.values.filter { it.state == "LIVE" && it.callId !in activeIds }.forEach { old ->
                existing[old.callId] = old.copy(
                    state = "CLOSED",
                    lastUpdatedAt = now,
                    closedAt = now,
                    closeReason = "LEFT_ACTIVE_RESEARCH_UNIVERSE"
                )
            }
        }

        val merged = existing.values
            .sortedWith(compareByDescending<RecommendationCall> { it.state == "LIVE" }.thenByDescending { it.lastUpdatedAt })
            .take(MAX_CALL_HISTORY)
        save(merged)
        return merged
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

            // If this app previously observed an open broker position for the call
            // and broker truth later reports it flat, the call is durably closed.
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
        private const val MAX_CALL_HISTORY = 250
    }
}
