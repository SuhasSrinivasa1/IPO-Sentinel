package com.suhas.iposentinel

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Instant

data class BrokerOrderTruth(
    val growwOrderId: String,
    val tradingSymbol: String,
    val orderStatus: String,
    val transactionType: String?,
    val quantity: Int?,
    val filledQuantity: Int?,
    val averageFillPrice: Double?,
    val createdAt: String?,
    val orderReferenceId: String?
)

data class BrokerPositionTruth(
    val tradingSymbol: String,
    val netQuantity: Int,
    val averagePrice: Double?
)

data class BrokerTruthSnapshot(
    val fetchedAt: String,
    val orders: List<BrokerOrderTruth> = emptyList(),
    val positions: List<BrokerPositionTruth> = emptyList(),
    val trigger: String,
    val error: String? = null
)

class BrokerTruthClient(context: Context) {
    private val appContext = context.applicationContext

    suspend fun fetch(trigger: String): BrokerTruthSnapshot {
        val now = Instant.now().toString()
        val groww = DirectGrowwClient(appContext)
        val (_, status) = groww.fetchStatus()
        if (status?.growwConfigured != true) {
            return BrokerTruthSnapshot(now, trigger = trigger, error = "Groww credentials are not configured")
        }

        val (authResult, token) = groww.accessToken()
        if (!authResult.ok || token.isNullOrBlank()) {
            return BrokerTruthSnapshot(now, trigger = trigger, error = authResult.error ?: "Groww authentication failed")
        }

        return try {
            val orders = getJson(
                "/v1/order/list?segment=CASH&page=0&page_size=100",
                token
            ).let(::parseOrders)
            val positions = getJson(
                "/v1/positions/user?segment=CASH",
                token
            ).let(::parsePositions)
            BrokerTruthSnapshot(now, orders, positions, trigger, null)
        } catch (e: Exception) {
            BrokerTruthSnapshot(now, trigger = trigger, error = e.message?.take(240) ?: e.javaClass.simpleName)
        }
    }

    private fun getJson(path: String, token: String): JSONObject {
        val connection = (URL(GROWW_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-API-VERSION", "1.0")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("Groww broker truth returned HTTP $code")
        }
        return JSONObject(body)
    }

    private fun parseOrders(root: JSONObject): List<BrokerOrderTruth> {
        val payload = root.optJSONObject("payload") ?: root
        val array = payload.optJSONArray("order_list") ?: payload.optJSONArray("orders") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val symbol = obj.optString("trading_symbol").trim()
                val id = obj.optString("groww_order_id").trim()
                if (symbol.isBlank() || id.isBlank()) continue
                add(
                    BrokerOrderTruth(
                        growwOrderId = id,
                        tradingSymbol = symbol,
                        orderStatus = obj.optString("order_status", "UNKNOWN"),
                        transactionType = obj.optString("transaction_type").ifBlank { null },
                        quantity = obj.optNullableInt("quantity"),
                        filledQuantity = obj.optNullableInt("filled_quantity"),
                        averageFillPrice = obj.optNullableDouble("average_fill_price"),
                        createdAt = obj.optString("created_at").ifBlank { obj.optString("exchange_time").ifBlank { null } },
                        orderReferenceId = obj.optString("order_reference_id").ifBlank { null }
                    )
                )
            }
        }
    }

    private fun parsePositions(root: JSONObject): List<BrokerPositionTruth> {
        val payload = root.optJSONObject("payload") ?: root
        val array = payload.optJSONArray("positions") ?: payload.optJSONArray("position_list") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val symbol = obj.optString("trading_symbol").trim()
                if (symbol.isBlank()) continue

                val explicit = listOf("net_quantity", "quantity", "net_qty")
                    .firstNotNullOfOrNull { key -> if (obj.has(key) && !obj.isNull(key)) obj.optInt(key) else null }
                val credit = obj.optInt("credit_quantity", 0) + obj.optInt("carry_forward_credit_quantity", 0)
                val debit = obj.optInt("debit_quantity", 0) + obj.optInt("carry_forward_debit_quantity", 0)
                val net = explicit ?: (credit - debit)

                val avg = listOf("average_price", "avg_price", "net_price", "credit_price")
                    .firstNotNullOfOrNull { key ->
                        if (obj.has(key) && !obj.isNull(key)) obj.optDouble(key) else null
                    }

                add(BrokerPositionTruth(symbol, net, avg))
            }
        }
    }

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name) else null

    companion object {
        private const val GROWW_BASE = "https://api.groww.in"
    }
}

class BrokerTruthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): BrokerTruthSnapshot? {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val ordersArray = obj.optJSONArray("orders") ?: JSONArray()
            val positionsArray = obj.optJSONArray("positions") ?: JSONArray()
            BrokerTruthSnapshot(
                fetchedAt = obj.optString("fetched_at"),
                orders = buildList {
                    for (i in 0 until ordersArray.length()) {
                        val row = ordersArray.optJSONObject(i) ?: continue
                        add(
                            BrokerOrderTruth(
                                growwOrderId = row.optString("groww_order_id"),
                                tradingSymbol = row.optString("trading_symbol"),
                                orderStatus = row.optString("order_status"),
                                transactionType = row.optString("transaction_type").ifBlank { null },
                                quantity = if (row.isNull("quantity")) null else row.optInt("quantity"),
                                filledQuantity = if (row.isNull("filled_quantity")) null else row.optInt("filled_quantity"),
                                averageFillPrice = if (row.isNull("average_fill_price")) null else row.optDouble("average_fill_price"),
                                createdAt = row.optString("created_at").ifBlank { null },
                                orderReferenceId = row.optString("order_reference_id").ifBlank { null }
                            )
                        )
                    }
                },
                positions = buildList {
                    for (i in 0 until positionsArray.length()) {
                        val row = positionsArray.optJSONObject(i) ?: continue
                        add(
                            BrokerPositionTruth(
                                tradingSymbol = row.optString("trading_symbol"),
                                netQuantity = row.optInt("net_quantity", 0),
                                averagePrice = if (row.isNull("average_price")) null else row.optDouble("average_price")
                            )
                        )
                    }
                },
                trigger = obj.optString("trigger"),
                error = obj.optString("error").ifBlank { null }
            )
        }.getOrNull()
    }

    fun save(snapshot: BrokerTruthSnapshot) {
        val orders = JSONArray()
        snapshot.orders.forEach { row ->
            orders.put(
                JSONObject()
                    .put("groww_order_id", row.growwOrderId)
                    .put("trading_symbol", row.tradingSymbol)
                    .put("order_status", row.orderStatus)
                    .put("transaction_type", row.transactionType)
                    .put("quantity", row.quantity)
                    .put("filled_quantity", row.filledQuantity)
                    .put("average_fill_price", row.averageFillPrice)
                    .put("created_at", row.createdAt)
                    .put("order_reference_id", row.orderReferenceId)
            )
        }
        val positions = JSONArray()
        snapshot.positions.forEach { row ->
            positions.put(
                JSONObject()
                    .put("trading_symbol", row.tradingSymbol)
                    .put("net_quantity", row.netQuantity)
                    .put("average_price", row.averagePrice)
            )
        }
        val json = JSONObject()
            .put("fetched_at", snapshot.fetchedAt)
            .put("trigger", snapshot.trigger)
            .put("error", snapshot.error)
            .put("orders", orders)
            .put("positions", positions)
        prefs.edit().putString(KEY_SNAPSHOT, json.toString()).commit()
    }

    fun lastAttemptMillis(): Long = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
    fun setLastAttemptMillis(value: Long) = prefs.edit().putLong(KEY_LAST_ATTEMPT, value).commit()

    companion object {
        private const val PREFS_NAME = "ipo_sentinel_broker_truth"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_LAST_ATTEMPT = "last_attempt"
    }
}

object RecoveryCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun request(context: Context, trigger: String, force: Boolean = false) {
        val appContext = context.applicationContext
        scope.launch { reconcile(appContext, trigger, force) }
    }

    suspend fun reconcile(context: Context, trigger: String, force: Boolean = false): BrokerTruthSnapshot? =
        mutex.withLock {
            val store = BrokerTruthStore(context)
            val now = System.currentTimeMillis()
            if (!force && now - store.lastAttemptMillis() < MIN_RECONCILE_INTERVAL_MS) {
                return@withLock store.load()
            }
            store.setLastAttemptMillis(now)

            val snapshot = BrokerTruthClient(context).fetch(trigger)
            store.save(snapshot)
            if (snapshot.error == null) {
                CallLedgerStore(context).reconcileBroker(snapshot)
            }
            AppAudit.log(
                context,
                "BROKER_TRUTH_RECONCILE",
                JSONObject()
                    .put("trigger", trigger)
                    .put("fetched_at", snapshot.fetchedAt)
                    .put("orders", snapshot.orders.size)
                    .put("positions", snapshot.positions.size)
                    .put("error", snapshot.error)
            )
            snapshot
        }

    private const val MIN_RECONCILE_INTERVAL_MS = 2L * 60L * 1000L
}
