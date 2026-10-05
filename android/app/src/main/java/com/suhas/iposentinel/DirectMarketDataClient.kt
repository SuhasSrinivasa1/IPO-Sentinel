package com.suhas.iposentinel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

data class MarketCandle(
    val timestamp: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class MarketSeries(
    val growwSymbol: String,
    val candles: List<MarketCandle>,
    val fetchedAt: String,
    val error: String? = null
)

class DirectMarketDataClient(context: Context) {
    private val appContext = context.applicationContext
    private val groww = DirectGrowwClient(appContext)
    private val archive = CandleArchiveStore(appContext)

    suspend fun fetchSessionCandles(
        growwSymbol: String,
        date: LocalDate,
        interval: String = "5minute"
    ): MarketSeries {
        val now = LocalDateTime.now(IST)
        val end = if (date == now.toLocalDate()) {
            now.coerceAtMost(LocalDateTime.of(date, LocalTime.of(15, 45)))
        } else {
            LocalDateTime.of(date, LocalTime.of(15, 45))
        }
        val start = LocalDateTime.of(date, LocalTime.of(9, 0))
        val series = fetchCandles(growwSymbol, start, end, interval)
        if (series.candles.isNotEmpty()) archive.save(series.candles, growwSymbol, interval)
        return series
    }

    suspend fun fetchRange(
        growwSymbol: String,
        startDate: LocalDate,
        endDate: LocalDate,
        interval: String = "5minute"
    ): MarketSeries = withContext(Dispatchers.IO) {
        if (endDate.isBefore(startDate)) {
            return@withContext MarketSeries(
                growwSymbol,
                emptyList(),
                Instant.now().toString(),
                "Invalid historical range"
            )
        }

        val out = mutableListOf<MarketCandle>()
        var cursor = startDate
        var lastError: String? = null
        while (!cursor.isAfter(endDate)) {
            val chunkEnd = minOf(cursor.plusDays(MAX_CHUNK_DAYS - 1L), endDate)
            val start = LocalDateTime.of(cursor, LocalTime.of(9, 0))
            val end = LocalDateTime.of(chunkEnd, LocalTime.of(15, 45))
            val part = fetchCandles(growwSymbol, start, end, interval)
            out += part.candles
            if (part.error != null) lastError = part.error
            cursor = chunkEnd.plusDays(1)
            if (!cursor.isAfter(endDate)) delay(120)
        }

        val candles = out.distinctBy { it.timestamp }.sortedBy { it.timestamp }
        if (candles.isNotEmpty()) archive.save(candles, growwSymbol, interval)
        MarketSeries(
            growwSymbol = growwSymbol,
            candles = candles,
            fetchedAt = Instant.now().toString(),
            error = if (out.isEmpty()) lastError else null
        )
    }

    private suspend fun fetchCandles(
        growwSymbol: String,
        start: LocalDateTime,
        end: LocalDateTime,
        interval: String
    ): MarketSeries = withContext(Dispatchers.IO) {
        val (auth, token) = groww.accessToken()
        if (!auth.ok || token.isNullOrBlank()) {
            return@withContext MarketSeries(
                growwSymbol,
                emptyList(),
                Instant.now().toString(),
                auth.error ?: "Groww authentication failed"
            )
        }

        val first = runCatching { request(growwSymbol, start, end, interval, token) }
        if (first.isSuccess) {
            return@withContext MarketSeries(
                growwSymbol,
                first.getOrThrow(),
                Instant.now().toString(),
                null
            )
        }

        val http = first.exceptionOrNull() as? MarketHttpException
        if (http?.code == 401 || http?.code == 403) {
            val (freshAuth, freshToken) = groww.accessToken(forceRefresh = true)
            if (freshAuth.ok && !freshToken.isNullOrBlank()) {
                val retry = runCatching { request(growwSymbol, start, end, interval, freshToken) }
                if (retry.isSuccess) {
                    return@withContext MarketSeries(
                        growwSymbol,
                        retry.getOrThrow(),
                        Instant.now().toString(),
                        null
                    )
                }
                return@withContext MarketSeries(
                    growwSymbol,
                    emptyList(),
                    Instant.now().toString(),
                    safeMessage(retry.exceptionOrNull())
                )
            }
        }

        MarketSeries(
            growwSymbol,
            emptyList(),
            Instant.now().toString(),
            safeMessage(first.exceptionOrNull())
        )
    }

    private fun request(
        growwSymbol: String,
        start: LocalDateTime,
        end: LocalDateTime,
        interval: String,
        token: String
    ): List<MarketCandle> {
        val path = buildString {
            append("/v1/historical/candles?exchange=NSE&segment=CASH")
            append("&groww_symbol=").append(enc(growwSymbol))
            append("&start_time=").append(enc(start.format(API_TIME)))
            append("&end_time=").append(enc(end.format(API_TIME)))
            append("&candle_interval=").append(enc(interval))
        }
        val connection = (URL(GROWW_BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-API-VERSION", "1.0")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw MarketHttpException(code, brokerMessage(body, code))

        val root = JSONObject(body)
        if (!root.optString("status", "SUCCESS").equals("SUCCESS", ignoreCase = true)) {
            throw IllegalStateException(root.optString("message", "Groww historical data failed"))
        }
        val payload = root.optJSONObject("payload") ?: root
        val array = payload.optJSONArray("candles") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONArray(i) ?: continue
                if (row.length() < 6) continue
                val timestamp = parseTimestamp(row.opt(i)) ?: continue
                val open = row.optDouble(1, Double.NaN)
                val high = row.optDouble(2, Double.NaN)
                val low = row.optDouble(3, Double.NaN)
                val close = row.optDouble(4, Double.NaN)
                val volume = max(0L, row.optLong(5, 0L))
                if (!open.isFinite() || !high.isFinite() || !low.isFinite() || !close.isFinite()) continue
                if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) continue
                add(MarketCandle(timestamp, open, high, low, close, volume))
            }
        }.sortedBy { it.timestamp }
    }

    private fun parseTimestamp(value: Any?): Instant? {
        return when (value) {
            is Number -> Instant.ofEpochSecond(value.toLong())
            is String -> {
                value.toLongOrNull()?.let { return Instant.ofEpochSecond(it) }
                runCatching {
                    LocalDateTime.parse(value, API_TIME).atZone(IST).toInstant()
                }.getOrNull()
            }
            else -> null
        }
    }

    private fun brokerMessage(body: String, code: Int): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        return json?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json?.optString("error")?.takeIf { it.isNotBlank() }
            ?: "Groww market data returned HTTP $code"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun safeMessage(error: Throwable?): String =
        error?.message?.take(240) ?: error?.javaClass?.simpleName ?: "Unknown market-data error"

    private class MarketHttpException(val code: Int, message: String) : IllegalStateException(message)

    companion object {
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        private val API_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private const val GROWW_BASE = "https://api.groww.in"
        private const val MAX_CHUNK_DAYS = 14L
        const val BENCHMARK_GROWW_SYMBOL = "NSE-NIFTY"
    }
}


data class CandleArchiveDay(
    val growwSymbol: String,
    val date: LocalDate,
    val interval: String,
    val candles: List<MarketCandle>,
    val archivedAt: String
)

class CandleArchiveStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "market_candle_archive").apply { mkdirs() }

    fun save(candles: List<MarketCandle>, growwSymbol: String, interval: String) {
        candles.groupBy { it.timestamp.atZone(DirectMarketDataClient.IST).toLocalDate() }
            .forEach { (date, rows) ->
                if (rows.isEmpty()) return@forEach
                val json = JSONObject()
                    .put("groww_symbol", growwSymbol)
                    .put("date", date.toString())
                    .put("interval", interval)
                    .put("archived_at", Instant.now().toString())
                    .put(
                        "candles",
                        JSONArray().apply {
                            rows.sortedBy { it.timestamp }.forEach { candle ->
                                put(
                                    JSONArray()
                                        .put(candle.timestamp.epochSecond)
                                        .put(candle.open)
                                        .put(candle.high)
                                        .put(candle.low)
                                        .put(candle.close)
                                        .put(candle.volume)
                                )
                            }
                        }
                    )
                val dir = File(root, safe(growwSymbol)).apply { mkdirs() }
                val target = File(dir, date.toString() + "_" + safe(interval) + ".json.gz")
                runCatching {
                    GZIPOutputStream(target.outputStream().buffered()).use {
                        it.write(json.toString().toByteArray(Charsets.UTF_8))
                    }
                }
            }
    }

    fun load(growwSymbol: String, date: LocalDate, interval: String = "5minute"): CandleArchiveDay? {
        val file = File(File(root, safe(growwSymbol)), date.toString() + "_" + safe(interval) + ".json.gz")
        if (!file.exists()) return null
        return runCatching {
            val text = GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            val array = json.optJSONArray("candles") ?: JSONArray()
            val candles = buildList {
                for (i in 0 until array.length()) {
                    val row = array.optJSONArray(i) ?: continue
                    if (row.length() < 6) continue
                    add(
                        MarketCandle(
                            timestamp = Instant.ofEpochSecond(row.optLong(0)),
                            open = row.optDouble(1),
                            high = row.optDouble(2),
                            low = row.optDouble(3),
                            close = row.optDouble(4),
                            volume = row.optLong(5)
                        )
                    )
                }
            }
            CandleArchiveDay(
                growwSymbol = json.optString("groww_symbol", growwSymbol),
                date = LocalDate.parse(json.optString("date", date.toString())),
                interval = json.optString("interval", interval),
                candles = candles,
                archivedAt = json.optString("archived_at")
            )
        }.getOrNull()
    }

    fun fileCount(): Int =
        root.walkTopDown().count { it.isFile && it.name.endsWith(".json.gz") }

    private fun safe(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}
