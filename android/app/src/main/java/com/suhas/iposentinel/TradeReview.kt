package com.suhas.iposentinel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import kotlin.math.floor

data class TradeReviewSettings(
    val budgetRupees: Int = DEFAULT_BUDGET_RUPEES,
    val liveReviewMode: Boolean = false
) {
    companion object {
        const val MIN_BUDGET_RUPEES = 1_000
        const val MAX_BUDGET_RUPEES = 100_000
        const val DEFAULT_BUDGET_RUPEES = 10_000
    }
}

data class GrowwTradingReadiness(
    val generatedAt: String,
    val growwAuthOk: Boolean,
    val staticIpMatches: Boolean,
    val staticIpConfirmed: Boolean,
    val nseEnabled: Boolean,
    val cashSegmentEnabled: Boolean,
    val ddpiEnabled: Boolean,
    val blockers: List<String>,
    val brokerMessage: String? = null
) {
    val brokerReady: Boolean
        get() = blockers.isEmpty()
}


class TradeReviewSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): TradeReviewSettings =
        TradeReviewSettings(
            budgetRupees = prefs.getInt(
                KEY_BUDGET,
                TradeReviewSettings.DEFAULT_BUDGET_RUPEES
            ).coerceIn(
                TradeReviewSettings.MIN_BUDGET_RUPEES,
                TradeReviewSettings.MAX_BUDGET_RUPEES
            ),
            liveReviewMode = prefs.getBoolean(KEY_LIVE_REVIEW_MODE, false)
        )

    fun saveBudget(value: Int): TradeReviewSettings {
        val normalized = ((value / 1_000.0).toInt() * 1_000)
            .coerceIn(
                TradeReviewSettings.MIN_BUDGET_RUPEES,
                TradeReviewSettings.MAX_BUDGET_RUPEES
            )
        prefs.edit().putInt(KEY_BUDGET, normalized).commit()
        return load()
    }

    fun saveLiveReviewMode(enabled: Boolean): TradeReviewSettings {
        prefs.edit().putBoolean(KEY_LIVE_REVIEW_MODE, enabled).commit()
        return load()
    }

    companion object {
        private const val PREFS_NAME = "ipo_sentinel_trade_review"
        private const val KEY_BUDGET = "budget_rupees"
        private const val KEY_LIVE_REVIEW_MODE = "live_review_mode"
    }
}

data class OrderReviewResult(
    val generatedAt: String,
    val callId: String,
    val tradingSymbol: String?,
    val growwSymbol: String?,
    val transactionType: String?,
    val product: String?,
    val segment: String = "CASH",
    val exchange: String = "NSE",
    val orderType: String = "MARKET",
    val validity: String = "DAY",
    val quantity: Int,
    val estimatedPrice: Double?,
    val estimatedNotional: Double?,
    val budgetRupees: Int,
    val availableBalance: Double?,
    val requiredMargin: Double?,
    val estimatedCharges: Double?,
    val orderReferenceId: String,
    val blockers: List<String>,
    val brokerMessage: String? = null
) {
    val ready: Boolean
        get() = blockers.isEmpty()

    fun brokerReadyText(): String = buildString {
        append("IPO Sentinel order review\n")
        append("Symbol: ").append(tradingSymbol ?: "—").append("\n")
        append("Groww: ").append(growwSymbol ?: "—").append("\n")
        append("Side: ").append(transactionType ?: "—").append("\n")
        append("Product: ").append(product ?: "—").append("\n")
        append("Exchange/Segment: ").append(exchange).append("/").append(segment).append("\n")
        append("Order type: ").append(orderType).append("\n")
        append("Validity: ").append(validity).append("\n")
        append("Quantity: ").append(quantity).append("\n")
        append("Reference price: ₹").append(estimatedPrice?.let { String.format("%.2f", it) } ?: "—").append("\n")
        append("Estimated notional: ₹").append(estimatedNotional?.let { String.format("%.2f", it) } ?: "—").append("\n")
        append("Required margin: ₹").append(requiredMargin?.let { String.format("%.2f", it) } ?: "—").append("\n")
        append("Available balance: ₹").append(availableBalance?.let { String.format("%.2f", it) } ?: "—").append("\n")
        append("Reference ID: ").append(orderReferenceId).append("\n")
        append("Generated: ").append(generatedAt).append("\n")
        if (blockers.isNotEmpty()) {
            append("BLOCKED: ").append(blockers.joinToString(", ")).append("\n")
        }
        append("No order was submitted by IPO Sentinel.")
    }
}

/**
 * Read-only pre-trade gateway.
 *
 * It validates an app-generated signal against broker cash/margin truth and creates an exact
 * broker-ready intent. It deliberately does NOT call Groww's order-create/modify/cancel endpoints.
 */
class TradeReviewGateway(context: Context) {
    private val appContext = context.applicationContext
    private val settings = TradeReviewSettingsStore(appContext)

    suspend fun readiness(): GrowwTradingReadiness = withContext(Dispatchers.IO) {
        val now = Instant.now().toString()
        val groww = DirectGrowwClient(appContext)
        val validation = groww.lastValidation()
        val blockers = mutableListOf<String>()

        val authOk = validation?.growwAuthOk == true
        val staticMatches = validation?.staticIpMatches == true
        val staticConfirmed = validation?.staticIpConfirmed == true

        if (!authOk) blockers += "GROWW_AUTH_NOT_VALIDATED"
        if (!staticMatches) blockers += "STATIC_IP_MISMATCH"
        if (!staticConfirmed) blockers += "STATIC_IP_NOT_CONFIRMED"

        val (auth, token) = groww.accessToken()
        if (!auth.ok || token.isNullOrBlank()) {
            blockers += "GROWW_AUTH_FAILED"
            return@withContext GrowwTradingReadiness(
                generatedAt = now,
                growwAuthOk = false,
                staticIpMatches = staticMatches,
                staticIpConfirmed = staticConfirmed,
                nseEnabled = false,
                cashSegmentEnabled = false,
                ddpiEnabled = false,
                blockers = blockers.distinct(),
                brokerMessage = auth.error
            )
        }

        val profileResult = runCatching { getJson("/v1/user/detail", token) }
        val profile = profileResult.getOrNull()
        val payload = profile?.optJSONObject("payload") ?: profile
        val nseEnabled = payload?.optBoolean("nse_enabled", false) == true
        val ddpiEnabled = payload?.optBoolean("ddpi_enabled", false) == true
        val segments = payload?.optJSONArray("active_segments") ?: JSONArray()
        val activeSegments = buildSet {
            for (i in 0 until segments.length()) {
                add(segments.optString(i).trim().uppercase())
            }
        }
        val cashEnabled = "CASH" in activeSegments

        if (profile == null) blockers += "USER_PROFILE_UNAVAILABLE"
        if (!nseEnabled) blockers += "NSE_NOT_ENABLED"
        if (!cashEnabled) blockers += "CASH_SEGMENT_NOT_ENABLED"
        if (!ddpiEnabled) blockers += "DDPI_NOT_ENABLED"

        GrowwTradingReadiness(
            generatedAt = now,
            growwAuthOk = true,
            staticIpMatches = staticMatches,
            staticIpConfirmed = staticConfirmed,
            nseEnabled = nseEnabled,
            cashSegmentEnabled = cashEnabled,
            ddpiEnabled = ddpiEnabled,
            blockers = blockers.distinct(),
            brokerMessage = profileResult.exceptionOrNull()?.message
        )
    }

    suspend fun review(call: RecommendationCall): OrderReviewResult = withContext(Dispatchers.IO) {
        val generatedAt = Instant.now().toString()
        val budget = settings.load().budgetRupees
        val blockers = mutableListOf<String>()

        val symbol = call.symbol?.trim()?.uppercase()
        val growwSymbol = call.growwSymbol?.trim()?.uppercase()
        if (call.state != "LIVE") blockers += "CALL_NOT_LIVE"
        if (symbol.isNullOrBlank() || growwSymbol != "NSE-" + symbol) blockers += "IDENTITY_NOT_EXACT"

        val side = when (call.direction.uppercase()) {
            "BUY" -> "BUY"
            "SELL" -> "SELL"
            else -> null
        }
        if (side == null) blockers += "UNSUPPORTED_DIRECTION"

        val product = when (side) {
            "BUY" -> "CNC"
            "SELL" -> "MIS"
            else -> null
        }

        val price = call.currentPrice ?: call.entryPrice
        if (price == null || price <= 0.0) blockers += "REFERENCE_PRICE_UNAVAILABLE"

        val lastUpdate = runCatching { Instant.parse(call.lastUpdatedAt) }.getOrNull()
        if (lastUpdate == null || Duration.between(lastUpdate, Instant.now()).abs().toMinutes() > MAX_QUOTE_AGE_MINUTES) {
            blockers += "MARKET_PRICE_STALE"
        }

        val quantity = if (price != null && price > 0.0) floor(budget / price).toInt() else 0
        if (quantity < 1) blockers += "BUDGET_BELOW_ONE_SHARE"

        val referenceId = "IPO-" + call.callId.hashCode().toUInt().toString(16).uppercase() +
            "-" + Instant.now().epochSecond.toString().takeLast(6)

        if (blockers.any { it in setOf("IDENTITY_NOT_EXACT", "REFERENCE_PRICE_UNAVAILABLE", "UNSUPPORTED_DIRECTION") }) {
            return@withContext OrderReviewResult(
                generatedAt = generatedAt,
                callId = call.callId,
                tradingSymbol = symbol,
                growwSymbol = growwSymbol,
                transactionType = side,
                product = product,
                quantity = quantity,
                estimatedPrice = price,
                estimatedNotional = if (price != null) price * quantity else null,
                budgetRupees = budget,
                availableBalance = null,
                requiredMargin = null,
                estimatedCharges = null,
                orderReferenceId = referenceId,
                blockers = blockers.distinct()
            )
        }

        val groww = DirectGrowwClient(appContext)
        val (auth, token) = groww.accessToken()
        if (!auth.ok || token.isNullOrBlank()) {
            blockers += "GROWW_AUTH_FAILED"
            return@withContext OrderReviewResult(
                generatedAt, call.callId, symbol, growwSymbol, side, product,
                quantity = quantity,
                estimatedPrice = price,
                estimatedNotional = price?.times(quantity),
                budgetRupees = budget,
                availableBalance = null,
                requiredMargin = null,
                estimatedCharges = null,
                orderReferenceId = referenceId,
                blockers = blockers.distinct(),
                brokerMessage = auth.error
            )
        }

        fun fetchWithToken(accessToken: String): Triple<JSONObject, JSONObject, String?> {
            val margin = getJson("/v1/margins/detail/user", accessToken)
            val body = JSONArray().put(
                JSONObject()
                    .put("trading_symbol", symbol)
                    .put("transaction_type", side)
                    .put("quantity", quantity)
                    .put("exchange", "NSE")
                    .put("product", product)
                    .put("order_type", "MARKET")
            )
            val required = postJson("/v1/margins/detail/orders?segment=CASH", body.toString(), accessToken)
            return Triple(margin, required, null)
        }

        val first = runCatching { fetchWithToken(token) }
        val brokerData = if (first.isSuccess) {
            first.getOrThrow()
        } else {
            val ex = first.exceptionOrNull() as? ReviewHttpException
            if (ex?.code == 401 || ex?.code == 403) {
                val (freshAuth, freshToken) = groww.accessToken(forceRefresh = true)
                if (freshAuth.ok && !freshToken.isNullOrBlank()) {
                    runCatching { fetchWithToken(freshToken) }.getOrElse { error ->
                        blockers += "BROKER_PREFLIGHT_FAILED"
                        Triple(JSONObject(), JSONObject(), error.message)
                    }
                } else {
                    blockers += "GROWW_REAUTH_FAILED"
                    Triple(JSONObject(), JSONObject(), freshAuth.error)
                }
            } else {
                blockers += "BROKER_PREFLIGHT_FAILED"
                Triple(JSONObject(), JSONObject(), first.exceptionOrNull()?.message)
            }
        }

        val marginPayload = brokerData.first.optJSONObject("payload") ?: brokerData.first
        val equity = marginPayload.optJSONObject("equity_margin_details")
        val available = when (product) {
            "CNC" -> equity?.optNullableDouble("cnc_balance_available")
            "MIS" -> equity?.optNullableDouble("mis_balance_available")
            else -> null
        } ?: marginPayload.optNullableDouble("clear_cash")

        val requiredPayload = brokerData.second.optJSONObject("payload") ?: brokerData.second
        val required = requiredPayload.optNullableDouble("total_requirement")
            ?: when (product) {
                "CNC" -> requiredPayload.optNullableDouble("cash_cnc_margin_required")
                "MIS" -> requiredPayload.optNullableDouble("cash_mis_margin_required")
                else -> null
            }
        val charges = requiredPayload.optNullableDouble("brokerage_and_charges")

        if (required == null) blockers += "MARGIN_REQUIREMENT_UNAVAILABLE"
        if (available == null) blockers += "AVAILABLE_BALANCE_UNAVAILABLE"
        if (available != null && required != null && available + 0.01 < required) blockers += "INSUFFICIENT_BROKER_BALANCE"

        OrderReviewResult(
            generatedAt = generatedAt,
            callId = call.callId,
            tradingSymbol = symbol,
            growwSymbol = growwSymbol,
            transactionType = side,
            product = product,
            quantity = quantity,
            estimatedPrice = price,
            estimatedNotional = price?.times(quantity),
            budgetRupees = budget,
            availableBalance = available,
            requiredMargin = required,
            estimatedCharges = charges,
            orderReferenceId = referenceId,
            blockers = blockers.distinct(),
            brokerMessage = brokerData.third
        )
    }

    private fun getJson(path: String, token: String): JSONObject =
        requestJson("GET", path, null, token)

    private fun postJson(path: String, body: String, token: String): JSONObject =
        requestJson("POST", path, body, token)

    private fun requestJson(method: String, path: String, body: String?, token: String): JSONObject {
        val connection = (URL(GROWW_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-API-VERSION", "1.0")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(response).optString("message") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "Groww preflight returned HTTP $code"
            throw ReviewHttpException(code, message)
        }
        return JSONObject(response)
    }

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name) else null

    private class ReviewHttpException(val code: Int, message: String) : IllegalStateException(message)

    companion object {
        private const val GROWW_BASE = "https://api.groww.in"
        private const val MAX_QUOTE_AGE_MINUTES = 15L
    }
}
