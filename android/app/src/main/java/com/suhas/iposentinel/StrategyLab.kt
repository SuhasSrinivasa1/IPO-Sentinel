package com.suhas.iposentinel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

data class MarketCandle(
    val growwSymbol: String,
    val timestamp: String,
    val epochSeconds: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class StrategyDefinition(
    val id: String,
    val name: String,
    val shortName: String,
    val phase: String,
    val thesis: String,
    val evidence: List<String>
)

data class StrategyReplayStats(
    val strategyId: String,
    val name: String,
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val expectancyBps: Double,
    val profitFactor: Double,
    val maxDrawdownBps: Double,
    val last20NetBps: Double,
    val status: String,
    val rankingScore: Double,
    val updatedAt: String?
)

data class StrategySignal(
    val candidateId: String,
    val symbol: String,
    val companyName: String,
    val board: String,
    val strategyId: String,
    val strategyName: String,
    val score: Double,
    val signalAt: String,
    val referencePrice: Double,
    val vwap: Double,
    val relativeVolume: Double,
    val reasonCodes: List<String>,
    val dataFresh: Boolean
)

data class ReplayMiss(
    val symbol: String,
    val companyName: String,
    val at: String,
    val forwardReturnBps: Double,
    val bestStrategyName: String,
    val bestScore: Double,
    val reason: String
)

data class ShadowReplaySummary(
    val generatedAt: String,
    val symbolsScanned: Int,
    val candlesStored: Int,
    val replayTrades: Int,
    val strategies: List<StrategyReplayStats>,
    val liveSignals: List<StrategySignal>,
    val missedOpportunities: List<ReplayMiss>,
    val errors: List<String>
)

object StrategyLibrary {
    val definitions = listOf(
        StrategyDefinition(
            id = "listing_momentum_stack",
            name = "Listing Momentum Stack",
            shortName = "Momentum",
            phase = "D0_D2",
            thesis = "Only follow listing strength when price acceptance, VWAP, breakout and participation agree.",
            evidence = listOf("VWAP hold", "opening-range breakout", "relative volume", "close near high")
        ),
        StrategyDefinition(
            id = "vwap_reclaim_stack",
            name = "VWAP Reclaim Stack",
            shortName = "VWAP Reclaim",
            phase = "D0_D10",
            thesis = "Buy-side research only after price regains VWAP with renewed volume and constructive candle structure.",
            evidence = listOf("VWAP reclaim", "volume expansion", "positive candle", "higher close")
        ),
        StrategyDefinition(
            id = "anchored_trend_stack",
            name = "Listing AVWAP Trend Stack",
            shortName = "AVWAP Trend",
            phase = "D1_D30",
            thesis = "Track sustained post-listing trends using listing-anchored VWAP, trend persistence and healthy pullbacks.",
            evidence = listOf("listing AVWAP", "higher lows", "trend persistence", "controlled pullback")
        ),
        StrategyDefinition(
            id = "volume_breakout_stack",
            name = "Volume Expansion Breakout",
            shortName = "Volume Breakout",
            phase = "D0_D30",
            thesis = "Require a real price breakout plus abnormal participation instead of treating volume alone as a signal.",
            evidence = listOf("20-bar breakout", "relative volume", "wide-range close", "VWAP confirmation")
        ),
        StrategyDefinition(
            id = "pullback_continuation_stack",
            name = "Pullback Continuation Stack",
            shortName = "Pullback",
            phase = "D1_D30",
            thesis = "Enter research after a controlled pullback holds trend value and buyers reassert control.",
            evidence = listOf("trend above AVWAP", "shallow retracement", "reclaim candle", "volume recovery")
        )
    )
}

class StrategyLabStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "ipo_sentinel_strategy_lab.db",
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE candles(
                groww_symbol TEXT NOT NULL,
                ts TEXT NOT NULL,
                epoch_seconds INTEGER NOT NULL,
                open REAL NOT NULL,
                high REAL NOT NULL,
                low REAL NOT NULL,
                close REAL NOT NULL,
                volume INTEGER NOT NULL,
                PRIMARY KEY(groww_symbol, epoch_seconds)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE replay_trades(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                strategy_id TEXT NOT NULL,
                groww_symbol TEXT NOT NULL,
                signal_ts TEXT NOT NULL,
                entry_ts TEXT NOT NULL,
                exit_ts TEXT NOT NULL,
                entry_price REAL NOT NULL,
                exit_price REAL NOT NULL,
                return_bps REAL NOT NULL,
                outcome TEXT NOT NULL,
                UNIQUE(strategy_id, groww_symbol, signal_ts)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE daily_reviews(
                day TEXT PRIMARY KEY,
                generated_at TEXT NOT NULL,
                summary_json TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun upsertCandles(candles: List<MarketCandle>) {
        if (candles.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            val statement = writableDatabase.compileStatement(
                """
                INSERT OR REPLACE INTO candles(
                    groww_symbol,ts,epoch_seconds,open,high,low,close,volume
                ) VALUES(?,?,?,?,?,?,?,?)
                """.trimIndent()
            )
            candles.forEach { candle ->
                statement.clearBindings()
                statement.bindString(1, candle.growwSymbol)
                statement.bindString(2, candle.timestamp)
                statement.bindLong(3, candle.epochSeconds)
                statement.bindDouble(4, candle.open)
                statement.bindDouble(5, candle.high)
                statement.bindDouble(6, candle.low)
                statement.bindDouble(7, candle.close)
                statement.bindLong(8, candle.volume)
                statement.executeInsert()
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun candles(symbol: String): List<MarketCandle> {
        val cursor = readableDatabase.rawQuery(
            """
            SELECT groww_symbol,ts,epoch_seconds,open,high,low,close,volume
            FROM candles
            WHERE groww_symbol=?
            ORDER BY epoch_seconds ASC
            """.trimIndent(),
            arrayOf(symbol)
        )
        return buildList {
            cursor.use {
                while (it.moveToNext()) {
                    add(
                        MarketCandle(
                            growwSymbol = it.getString(0),
                            timestamp = it.getString(1),
                            epochSeconds = it.getLong(2),
                            open = it.getDouble(3),
                            high = it.getDouble(4),
                            low = it.getDouble(5),
                            close = it.getDouble(6),
                            volume = it.getLong(7)
                        )
                    )
                }
            }
        }
    }

    fun allSymbols(): List<String> {
        val cursor = readableDatabase.rawQuery(
            "SELECT DISTINCT groww_symbol FROM candles ORDER BY groww_symbol",
            emptyArray()
        )
        return buildList {
            cursor.use {
                while (it.moveToNext()) add(it.getString(0))
            }
        }
    }

    fun candleCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM candles", emptyArray())
        cursor.use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun clearReplayForSymbol(symbol: String) {
        writableDatabase.delete("replay_trades", "groww_symbol=?", arrayOf(symbol))
    }

    fun insertReplayTrade(
        strategyId: String,
        symbol: String,
        signalTs: String,
        entryTs: String,
        exitTs: String,
        entryPrice: Double,
        exitPrice: Double,
        returnBps: Double
    ) {
        val outcome = when {
            returnBps > 0.0 -> "WIN"
            returnBps < 0.0 -> "LOSS"
            else -> "FLAT"
        }
        writableDatabase.execSQL(
            """
            INSERT OR REPLACE INTO replay_trades(
                strategy_id,groww_symbol,signal_ts,entry_ts,exit_ts,
                entry_price,exit_price,return_bps,outcome
            ) VALUES(?,?,?,?,?,?,?,?,?)
            """.trimIndent(),
            arrayOf(
                strategyId, symbol, signalTs, entryTs, exitTs,
                entryPrice, exitPrice, returnBps, outcome
            )
        )
    }

    fun strategyStats(): List<StrategyReplayStats> =
        StrategyLibrary.definitions.map { definition ->
            val cursor = readableDatabase.rawQuery(
                """
                SELECT return_bps,outcome
                FROM replay_trades
                WHERE strategy_id=?
                ORDER BY id ASC
                """.trimIndent(),
                arrayOf(definition.id)
            )
            val returns = mutableListOf<Double>()
            var wins = 0
            var losses = 0
            cursor.use {
                while (it.moveToNext()) {
                    val value = it.getDouble(0)
                    returns += value
                    if (value > 0) wins++ else if (value < 0) losses++
                }
            }
            val trades = returns.size
            val positive = returns.filter { it > 0 }.sum()
            val negative = -returns.filter { it < 0 }.sum()
            val expectancy = if (trades > 0) returns.average() else 0.0
            val profitFactor = when {
                negative > 0.0 -> positive / negative
                positive > 0.0 -> 99.0
                else -> 0.0
            }
            var equity = 0.0
            var peak = 0.0
            var maxDrawdown = 0.0
            returns.forEach { value ->
                equity += value
                peak = max(peak, equity)
                maxDrawdown = max(maxDrawdown, peak - equity)
            }
            val winRate = if (trades > 0) wins * 100.0 / trades else 0.0
            val last20 = returns.takeLast(20).sum()
            val status = when {
                trades >= 30 && expectancy >= 20.0 && winRate >= 55.0 && profitFactor >= 1.25 -> "CHAMPION"
                trades >= 12 && expectancy > 0.0 && profitFactor >= 1.05 -> "CHALLENGER"
                trades > 0 -> "REPLAYING"
                else -> "RESEARCH"
            }
            val ranking = (
                min(trades.toDouble(), 40.0) * 0.5 +
                    winRate * 0.25 +
                    expectancy.coerceIn(-100.0, 200.0) * 0.15 +
                    min(profitFactor, 3.0) * 10.0
                ).coerceIn(0.0, 100.0)
            StrategyReplayStats(
                strategyId = definition.id,
                name = definition.name,
                trades = trades,
                wins = wins,
                losses = losses,
                winRatePct = winRate,
                expectancyBps = expectancy,
                profitFactor = profitFactor,
                maxDrawdownBps = maxDrawdown,
                last20NetBps = last20,
                status = status,
                rankingScore = ranking,
                updatedAt = Instant.now().toString()
            )
        }.sortedByDescending { it.rankingScore }

    fun totalReplayTrades(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM replay_trades", emptyArray())
        cursor.use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun saveDailyReview(summary: ShadowReplaySummary) {
        val obj = JSONObject()
            .put("generated_at", summary.generatedAt)
            .put("symbols_scanned", summary.symbolsScanned)
            .put("candles_stored", summary.candlesStored)
            .put("replay_trades", summary.replayTrades)
            .put("live_signals", summary.liveSignals.size)
            .put("missed_opportunities", summary.missedOpportunities.size)
            .put("errors", JSONArray(summary.errors))
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO daily_reviews(day,generated_at,summary_json) VALUES(?,?,?)",
            arrayOf(LocalDate.now(IST).toString(), summary.generatedAt, obj.toString())
        )
    }

    companion object {
        private val IST = ZoneId.of("Asia/Kolkata")
    }
}

class GrowwHistoricalClient(private val context: Context) {
    suspend fun fetchFiveMinuteCandles(
        growwSymbol: String,
        start: LocalDateTime,
        end: LocalDateTime
    ): Pair<List<MarketCandle>, String?> = withContext(Dispatchers.IO) {
        val groww = DirectGrowwClient(context)
        val (auth, token) = groww.accessToken()
        if (!auth.ok || token.isNullOrBlank()) {
            return@withContext emptyList<MarketCandle>() to (auth.error ?: "Groww authentication failed")
        }
        runCatching { requestCandles(growwSymbol, start, end, token) }
            .fold(
                onSuccess = { it to null },
                onFailure = { error ->
                    if ((error as? MarketHttpException)?.code in setOf(401, 403)) {
                        val (freshAuth, freshToken) = groww.accessToken(forceRefresh = true)
                        if (freshAuth.ok && !freshToken.isNullOrBlank()) {
                            runCatching { requestCandles(growwSymbol, start, end, freshToken) }
                                .fold(
                                    onSuccess = { it to null },
                                    onFailure = { emptyList<MarketCandle>() to safeMessage(it) }
                                )
                        } else {
                            emptyList<MarketCandle>() to (freshAuth.error ?: "Groww re-authentication failed")
                        }
                    } else {
                        emptyList<MarketCandle>() to safeMessage(error)
                    }
                }
            )
    }

    private fun requestCandles(
        growwSymbol: String,
        start: LocalDateTime,
        end: LocalDateTime,
        token: String
    ): List<MarketCandle> {
        fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val url = URL(
            GROWW_BASE + "/v1/historical/candles" +
                "?exchange=NSE&segment=CASH" +
                "&groww_symbol=" + enc(growwSymbol) +
                "&start_time=" + enc(start.format(formatter)) +
                "&end_time=" + enc(end.format(formatter)) +
                "&candle_interval=5minute"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-API-VERSION", "1.0")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw MarketHttpException(code, "Historical candles HTTP $code")
        val root = JSONObject(body)
        val payload = root.optJSONObject("payload") ?: root
        val candles = payload.optJSONArray("candles") ?: JSONArray()
        return buildList {
            for (i in 0 until candles.length()) {
                val row = candles.optJSONArray(i) ?: continue
                if (row.length() < 6) continue
                val timestamp = row.optString(0)
                val epoch = parseCandleEpoch(timestamp) ?: continue
                add(
                    MarketCandle(
                        growwSymbol = growwSymbol,
                        timestamp = timestamp,
                        epochSeconds = epoch,
                        open = row.optDouble(1),
                        high = row.optDouble(2),
                        low = row.optDouble(3),
                        close = row.optDouble(4),
                        volume = row.optLong(5)
                    )
                )
            }
        }
    }

    private fun parseCandleEpoch(value: String): Long? {
        value.toLongOrNull()?.let { return if (it > 10_000_000_000L) it / 1000 else it }
        val normalized = value.replace("T", " ").take(19)
        return runCatching {
            LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(IST)
                .toEpochSecond()
        }.getOrNull()
    }

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).replace(Regex("\\s+"), " ").take(240)

    private class MarketHttpException(val code: Int, message: String) : IllegalStateException(message)

    companion object {
        private const val GROWW_BASE = "https://api.groww.in"
        private val IST = ZoneId.of("Asia/Kolkata")
    }
}

class StrategyLabEngine(context: Context) {
    private val appContext = context.applicationContext
    private val store = StrategyLabStore(appContext)
    private val historical = GrowwHistoricalClient(appContext)

    suspend fun refresh(plan: ResearchPlan?): ShadowReplaySummary {
        val generatedAt = Instant.now().toString()
        if (plan == null) {
            return ShadowReplaySummary(
                generatedAt, 0, store.candleCount(), store.totalReplayTrades(),
                store.strategyStats(), emptyList(), emptyList(), listOf("Research plan unavailable")
            )
        }

        val candidates = plan.allKnownCandidates
            .filter { it.symbolResolved && !it.growwSymbol.isNullOrBlank() }
            .filter {
                val day = it.tradingDayNumber ?: return@filter false
                day in 0..30
            }
            .distinctBy { it.growwSymbol }

        val errors = mutableListOf<String>()
        var scanned = 0
        val now = LocalDateTime.now(IST)

        candidates.take(MAX_SYMBOLS_PER_REFRESH).forEach { candidate ->
            val symbol = candidate.growwSymbol ?: return@forEach
            val listingDate = candidate.listingDate
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: now.toLocalDate().minusDays(14)
            val startDate = maxOf(listingDate, now.toLocalDate().minusDays(14))
            val start = startDate.atTime(9, 15)
            val (candles, error) = historical.fetchFiveMinuteCandles(symbol, start, now)
            if (candles.isNotEmpty()) {
                store.upsertCandles(candles)
                replaySymbol(symbol)
                scanned++
            }
            if (error != null) errors += "$symbol: $error"
        }

        val liveSignals = candidates.mapNotNull { candidate ->
            latestSignal(candidate)
        }.sortedByDescending { it.score }

        val misses = candidates.mapNotNull { candidate -> biggestMiss(candidate) }
            .sortedByDescending { it.forwardReturnBps }
            .take(10)

        val result = ShadowReplaySummary(
            generatedAt = generatedAt,
            symbolsScanned = scanned,
            candlesStored = store.candleCount(),
            replayTrades = store.totalReplayTrades(),
            strategies = store.strategyStats(),
            liveSignals = liveSignals,
            missedOpportunities = misses,
            errors = errors.distinct().take(20)
        )
        store.saveDailyReview(result)
        return result
    }

    fun cached(): ShadowReplaySummary {
        return ShadowReplaySummary(
            generatedAt = Instant.now().toString(),
            symbolsScanned = store.allSymbols().size,
            candlesStored = store.candleCount(),
            replayTrades = store.totalReplayTrades(),
            strategies = store.strategyStats(),
            liveSignals = emptyList(),
            missedOpportunities = emptyList(),
            errors = emptyList()
        )
    }

    private fun replaySymbol(symbol: String) {
        val candles = store.candles(symbol)
        if (candles.size < MIN_BARS) return
        store.clearReplayForSymbol(symbol)

        for (index in MIN_BARS until candles.lastIndex - EXIT_HORIZON_BARS) {
            val evaluations = evaluateAt(candles, index)
            evaluations.filter { it.second >= REPLAY_SIGNAL_THRESHOLD }.forEach { (definition, _) ->
                val entry = candles[index + 1]
                val exitIndex = min(index + 1 + EXIT_HORIZON_BARS, candles.lastIndex)
                val exit = candles[exitIndex]
                if (entry.open <= 0.0) return@forEach
                val returnBps = ((exit.close / entry.open) - 1.0) * 10_000.0
                store.insertReplayTrade(
                    strategyId = definition.id,
                    symbol = symbol,
                    signalTs = candles[index].timestamp,
                    entryTs = entry.timestamp,
                    exitTs = exit.timestamp,
                    entryPrice = entry.open,
                    exitPrice = exit.close,
                    returnBps = returnBps
                )
            }
        }
    }

    private fun biggestMiss(candidate: ResearchCandidate): ReplayMiss? {
        val growwSymbol = candidate.growwSymbol ?: return null
        val candles = store.candles(growwSymbol)
        if (candles.size < MIN_BARS + EXIT_HORIZON_BARS + 1) return null

        var bestMiss: ReplayMiss? = null
        for (index in MIN_BARS until candles.lastIndex - EXIT_HORIZON_BARS) {
            val evaluations = evaluateAt(candles, index)
            val best = evaluations.maxByOrNull { it.second } ?: continue
            if (best.second >= REPLAY_SIGNAL_THRESHOLD) continue

            val start = candles[index].close
            val future = candles[index + EXIT_HORIZON_BARS].close
            if (start <= 0.0) continue
            val moveBps = ((future / start) - 1.0) * 10_000.0
            if (moveBps < MISSED_MOVE_THRESHOLD_BPS) continue

            val miss = ReplayMiss(
                symbol = candidate.symbol ?: growwSymbol.removePrefix("NSE-"),
                companyName = candidate.companyName,
                at = candles[index].timestamp,
                forwardReturnBps = moveBps,
                bestStrategyName = best.first.name,
                bestScore = best.second,
                reason = when {
                    best.second >= 60.0 -> "NEAR_THRESHOLD"
                    metrics(candles, index).relativeVolume < 1.1 -> "LOW_VOLUME_CONFIRMATION"
                    !metrics(candles, index).closeAboveVwap -> "BELOW_VWAP"
                    else -> "SETUP_NOT_RECOGNIZED"
                }
            )
            if (bestMiss == null || miss.forwardReturnBps > bestMiss!!.forwardReturnBps) bestMiss = miss
        }
        return bestMiss
    }

    private fun latestSignal(candidate: ResearchCandidate): StrategySignal? {
        val symbol = candidate.growwSymbol ?: return null
        val candles = store.candles(symbol)
        if (candles.size < MIN_BARS) return null
        val index = candles.lastIndex
        val evaluations = evaluateAt(candles, index)
        val best = evaluations.maxByOrNull { it.second } ?: return null
        if (best.second < LIVE_SIGNAL_THRESHOLD) return null

        val candle = candles[index]
        val ageSeconds = Instant.now().epochSecond - candle.epochSeconds
        val metrics = metrics(candles, index)
        return StrategySignal(
            candidateId = candidate.candidateId,
            symbol = candidate.symbol ?: symbol.removePrefix("NSE-"),
            companyName = candidate.companyName,
            board = if (candidate.isSme) "SME" else "MAINBOARD",
            strategyId = best.first.id,
            strategyName = best.first.name,
            score = best.second,
            signalAt = candle.timestamp,
            referencePrice = candle.close,
            vwap = metrics.vwap,
            relativeVolume = metrics.relativeVolume,
            reasonCodes = reasonsFor(best.first.id, metrics),
            dataFresh = ageSeconds in 0..LIVE_FRESHNESS_SECONDS
        )
    }

    private fun evaluateAt(
        candles: List<MarketCandle>,
        index: Int
    ): List<Pair<StrategyDefinition, Double>> {
        val m = metrics(candles, index)
        return StrategyLibrary.definitions.map { definition ->
            val score = when (definition.id) {
                "listing_momentum_stack" -> score(
                    m.closeAboveVwap to 22.0,
                    m.breakout20 to 24.0,
                    (m.relativeVolume >= 1.5) to 22.0,
                    m.closeNearHigh to 16.0,
                    m.positiveMomentum to 16.0
                )
                "vwap_reclaim_stack" -> score(
                    m.vwapReclaim to 30.0,
                    (m.relativeVolume >= 1.25) to 22.0,
                    m.positiveCandle to 16.0,
                    m.higherClose to 16.0,
                    m.closeNearHigh to 16.0
                )
                "anchored_trend_stack" -> score(
                    m.closeAboveAvwap to 28.0,
                    m.higherLowStructure to 24.0,
                    m.positiveMomentum to 18.0,
                    (m.pullbackPct in 0.0..3.5) to 16.0,
                    m.closeAboveVwap to 14.0
                )
                "volume_breakout_stack" -> score(
                    m.breakout20 to 30.0,
                    (m.relativeVolume >= 1.8) to 28.0,
                    m.wideRangeClose to 18.0,
                    m.closeAboveVwap to 14.0,
                    m.closeNearHigh to 10.0
                )
                "pullback_continuation_stack" -> score(
                    m.closeAboveAvwap to 24.0,
                    (m.pullbackPct in 0.5..4.0) to 22.0,
                    m.vwapReclaim to 22.0,
                    (m.relativeVolume >= 1.1) to 16.0,
                    m.positiveCandle to 16.0
                )
                else -> 0.0
            }
            definition to score
        }
    }

    private fun score(vararg conditions: Pair<Boolean, Double>): Double =
        conditions.sumOf { if (it.first) it.second else 0.0 }.coerceIn(0.0, 100.0)

    private data class Metrics(
        val vwap: Double,
        val avwap: Double,
        val relativeVolume: Double,
        val closeAboveVwap: Boolean,
        val closeAboveAvwap: Boolean,
        val vwapReclaim: Boolean,
        val breakout20: Boolean,
        val closeNearHigh: Boolean,
        val positiveMomentum: Boolean,
        val positiveCandle: Boolean,
        val higherClose: Boolean,
        val higherLowStructure: Boolean,
        val wideRangeClose: Boolean,
        val pullbackPct: Double
    )

    private fun metrics(candles: List<MarketCandle>, index: Int): Metrics {
        val current = candles[index]
        val start = max(0, index - 19)
        val window = candles.subList(start, index + 1)
        val prior = candles.getOrNull(index - 1)
        val prior20 = candles.subList(start, max(start, index))

        val pv = window.sumOf { typical(it) * it.volume.toDouble() }
        val volume = window.sumOf { it.volume.toDouble() }.coerceAtLeast(1.0)
        val vwap = pv / volume

        val anchored = candles.subList(0, index + 1)
        val anchoredPv = anchored.sumOf { typical(it) * it.volume.toDouble() }
        val anchoredVolume = anchored.sumOf { it.volume.toDouble() }.coerceAtLeast(1.0)
        val avwap = anchoredPv / anchoredVolume

        val averageVolume = prior20.takeLast(10).map { it.volume.toDouble() }
            .average().takeIf { !it.isNaN() && it > 0 } ?: current.volume.toDouble().coerceAtLeast(1.0)
        val relativeVolume = current.volume / averageVolume

        val priorHigh = prior20.maxOfOrNull { it.high } ?: current.high
        val recentHigh = window.maxOfOrNull { it.high } ?: current.high
        val recentLow = window.minOfOrNull { it.low } ?: current.low
        val range = (current.high - current.low).coerceAtLeast(0.0001)
        val closeLocation = (current.close - current.low) / range
        val avgRange = prior20.takeLast(10).map { it.high - it.low }
            .average().takeIf { !it.isNaN() && it > 0 } ?: range

        val peak = window.maxOfOrNull { it.high } ?: current.high
        val pullbackPct = if (peak > 0) ((peak - current.close) / peak * 100.0).coerceAtLeast(0.0) else 0.0

        val last3 = candles.subList(max(0, index - 2), index + 1)
        val higherLows = last3.size >= 3 &&
            last3[1].low >= last3[0].low &&
            last3[2].low >= last3[1].low

        return Metrics(
            vwap = vwap,
            avwap = avwap,
            relativeVolume = relativeVolume,
            closeAboveVwap = current.close >= vwap,
            closeAboveAvwap = current.close >= avwap,
            vwapReclaim = prior != null && prior.close < vwap && current.close > vwap,
            breakout20 = prior20.isNotEmpty() && current.close > priorHigh,
            closeNearHigh = closeLocation >= 0.72,
            positiveMomentum = prior != null && current.close > prior.close && current.close > current.open,
            positiveCandle = current.close > current.open,
            higherClose = prior != null && current.close > prior.close,
            higherLowStructure = higherLows,
            wideRangeClose = range >= avgRange * 1.35 && closeLocation >= 0.65,
            pullbackPct = pullbackPct
        )
    }

    private fun reasonsFor(strategyId: String, m: Metrics): List<String> = buildList {
        if (m.closeAboveVwap) add("ABOVE_VWAP")
        if (m.closeAboveAvwap) add("ABOVE_LISTING_AVWAP")
        if (m.vwapReclaim) add("VWAP_RECLAIM")
        if (m.breakout20) add("20_BAR_BREAKOUT")
        if (m.relativeVolume >= 1.5) add("RVOL_" + String.format("%.2f", m.relativeVolume))
        if (m.closeNearHigh) add("CLOSE_NEAR_HIGH")
        if (m.higherLowStructure) add("HIGHER_LOWS")
        if (m.wideRangeClose) add("WIDE_RANGE_CLOSE")
        add("ENSEMBLE_" + strategyId.uppercase())
    }

    companion object {
        private val IST = ZoneId.of("Asia/Kolkata")
        private const val MAX_SYMBOLS_PER_REFRESH = 20
        private const val MIN_BARS = 20
        private const val EXIT_HORIZON_BARS = 6
        private const val REPLAY_SIGNAL_THRESHOLD = 70.0
        private const val LIVE_SIGNAL_THRESHOLD = 75.0
        private const val MISSED_MOVE_THRESHOLD_BPS = 200.0
        private const val LIVE_FRESHNESS_SECONDS = 15L * 60L

        private fun typical(candle: MarketCandle): Double =
            (candle.high + candle.low + candle.close) / 3.0
    }
}
