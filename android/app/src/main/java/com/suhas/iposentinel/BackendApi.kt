package com.suhas.iposentinel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ApiResult(
    val ok: Boolean,
    val statusCode: Int,
    val body: String,
    val error: String? = null
)

data class ConnectionStatus(
    val secretStoreReady: Boolean = false,
    val growwConfigured: Boolean = false,
    val expectedStaticIp: String? = null,
    val staticIpConfirmed: Boolean = false,
    val error: String? = null
)

data class StrategyFamilyStats(
    val familyId: String,
    val name: String,
    val phase: String,
    val description: String,
    val trades: Int,
    val winRatePct: Double,
    val expectancyBps: Double,
    val profitFactor: Double,
    val maxDrawdownBps: Double,
    val last20NetBps: Double,
    val status: String,
    val rankingScore: Double
)

data class StrategySummary(
    val totalStrategyFamilies: Int = 0,
    val testedFamilies: Int = 0,
    val champions: Int = 0,
    val challengers: Int = 0,
    val untestedFamilies: Int = 0,
    val topFive: List<StrategyFamilyStats> = emptyList(),
    val families: List<StrategyFamilyStats> = emptyList(),
    val rankingNote: String = ""
)

data class LiveStateStatus(
    val enabled: Boolean = false,
    val budgetRupees: Int = 100_000,
    val updatedAt: String? = null,
    val eventId: Long = 0L
)

data class OrderLifecycleEvent(
    val id: Long,
    val timestamp: String,
    val eventType: String,
    val symbol: String? = null,
    val side: String? = null,
    val quantity: Int? = null,
    val price: Double? = null,
    val orderId: String? = null,
    val message: String? = null
)

data class OrderEventBatch(
    val events: List<OrderLifecycleEvent>,
    val lastId: Long
)

data class DailyGoalStatus(
    val date: String = "",
    val targetRupees: Double = 5000.0,
    val realizedNetPnl: Double = 0.0,
    val remainingRupees: Double = 5000.0,
    val targetAchieved: Boolean = false,
    val returnOnBudgetPct: Double = 0.0,
    val closedCalls: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0
)

data class OpportunitySignal(
    val symbol: String,
    val companyName: String,
    val direction: String,
    val action: String,
    val score: Double,
    val confidence: Double,
    val entryPrice: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val quantity: Int,
    val expectedNetAtTarget2: Double,
    val minimumNetEdgeRupees: Double,
    val relativeVolume: Double,
    val spreadBps: Double,
    val tradingDayNumber: Int,
    val isSme: Boolean,
    val reasonCodes: List<String>
)

data class ResearchPick(
    val symbol: String?,
    val companyName: String,
    val direction: String?,
    val researchScore: Double,
    val preMarketBias: String?,
    val entryPrice: Double?,
    val stopLoss: Double?,
    val target1: Double?,
    val target2: Double?,
    val quantity: Int?,
    val confidence: Double?,
    val tradingDayNumber: Int?,
    val board: String?,
    val reasons: List<String>,
    val note: String?
)

data class OwnedIpoPosition(
    val symbol: String,
    val quantity: Int,
    val avgPrice: Double,
    val mark: Double,
    val unrealizedPnl: Double
)

data class ClosedIpoCall(
    val symbol: String,
    val direction: String,
    val quantity: Int,
    val entryTimestamp: String?,
    val exitTimestamp: String?,
    val entryPrice: Double,
    val exitPrice: Double,
    val netPnl: Double,
    val outcome: String
)

data class LearningRecord(
    val at: String?,
    val type: String,
    val summary: String
)

data class ResearchDashboard(
    val generatedAt: String?,
    val dailyGoal: DailyGoalStatus,
    val activeSignals: List<OpportunitySignal>,
    val topThree: List<ResearchPick>,
    val tomorrowCandidates: List<ResearchCandidate>,
    val active30dCandidates: List<ResearchCandidate>,
    val openPositions: List<OwnedIpoPosition>,
    val closedCalls: List<ClosedIpoCall>,
    val dailyLearning: List<LearningRecord>,
    val weeklyLearning: List<LearningRecord>,
    val mainboardCoverage: Int,
    val smeCoverage: Int,
    val coverageRule: String
)

data class ResearchCandidate(
    val candidateId: String,
    val lifecycleState: String,
    val symbol: String?,
    val companyName: String,
    val listingDate: String?,
    val issueStartDate: String?,
    val issueEndDate: String?,
    val officialIssueId: String?,
    val isin: String?,
    val board: String?,
    val isSme: Boolean,
    val issueStatus: String?,
    val nseListingConfirmed: Boolean,
    val growwSymbol: String?,
    val growwSeries: String?,
    val buyAllowed: Boolean,
    val sellAllowed: Boolean,
    val symbolResolved: Boolean,
    val growwResolutionStatus: String,
    val resolutionStatus: String,
    val issuePriceText: String? = null,
    val subscriptionMultiple: Double? = null,
    val tradingDayNumber: Int? = null,
    val growwLotSize: Int? = null
)

data class ResearchPlan(
    val generatedAt: String? = null,
    val sourceReady: Boolean = false,
    val researchHealth: String = "UNKNOWN",
    val calendarReady: Boolean = false,
    val nextTradingDay: String? = null,
    val candidateCount: Int = 0,
    val nseIdentityConfirmedCount: Int = 0,
    val growwResolvedCount: Int = 0,
    val growwPendingCount: Int = 0,
    val nextTradingDayCandidates: List<ResearchCandidate> = emptyList(),
    val weekCandidates: List<ResearchCandidate> = emptyList(),
    val allKnownCandidates: List<ResearchCandidate> = emptyList(),
    val errors: List<String> = emptyList()
)

data class ValidationStatus(
    val growwAuthOk: Boolean = false,
    val detectedEgressIp: String? = null,
    val expectedStaticIp: String? = null,
    val staticIpMatches: Boolean = false,
    val staticIpConfirmed: Boolean = false,
    val secretStoreReady: Boolean = false,
    val calendarReady: Boolean = false,
    val nseIdentitySourceReady: Boolean = false,
    val liveExecutionReady: Boolean = false,
    val growwError: String? = null,
    val egressError: String? = null
)

class BackendApi {
    suspend fun saveGrowwSettings(
        totpToken: String,
        totpSecret: String,
        expectedStaticIp: String,
        staticIpConfirmed: Boolean
    ): ApiResult {
        val payload = JSONObject()
            .put("totp_token", totpToken.trim())
            .put("totp_secret", totpSecret.replace(" ", "").trim())
            .put("expected_static_ip", expectedStaticIp.trim())
            .put("static_ip_confirmed", staticIpConfirmed)
            .toString()
        return request("POST", "/settings/groww", payload)
    }

    suspend fun fetchStatus(): Pair<ApiResult, ConnectionStatus?> {
        val result = request("GET", "/settings/status", null)
        if (!result.ok) return result to null
        val json = JSONObject(result.body)
        return result to ConnectionStatus(
            secretStoreReady = json.optBoolean("secret_store_ready", false),
            growwConfigured = json.optBoolean("groww_configured", false),
            expectedStaticIp = json.optString("expected_static_ip").ifBlank { null },
            staticIpConfirmed = json.optBoolean("static_ip_confirmed", false),
            error = json.optString("error").ifBlank { null }
        )
    }

    suspend fun fetchStrategySummary(): Pair<ApiResult, StrategySummary?> {
        val result = request("GET", "/strategies/summary", null)
        if (!result.ok) return result to null
        val json = JSONObject(result.body)

        fun parseFamily(obj: JSONObject): StrategyFamilyStats =
            StrategyFamilyStats(
                familyId = obj.optString("family_id"),
                name = obj.optString("name"),
                phase = obj.optString("phase"),
                description = obj.optString("description"),
                trades = obj.optInt("trades", 0),
                winRatePct = obj.optDouble("win_rate_pct", 0.0),
                expectancyBps = obj.optDouble("expectancy_bps", 0.0),
                profitFactor = obj.optDouble("profit_factor", 0.0),
                maxDrawdownBps = obj.optDouble("max_drawdown_bps", 0.0),
                last20NetBps = obj.optDouble("last_20_net_bps", 0.0),
                status = obj.optString("status", "RESEARCH"),
                rankingScore = obj.optDouble("ranking_score", 0.0)
            )

        val topFiveJson = json.optJSONArray("top_five")
        val familiesJson = json.optJSONArray("families")
        val topFive = buildList {
            if (topFiveJson != null) {
                for (i in 0 until topFiveJson.length()) {
                    add(parseFamily(topFiveJson.getJSONObject(i)))
                }
            }
        }
        val families = buildList {
            if (familiesJson != null) {
                for (i in 0 until familiesJson.length()) {
                    add(parseFamily(familiesJson.getJSONObject(i)))
                }
            }
        }

        return result to StrategySummary(
            totalStrategyFamilies = json.optInt("total_strategy_families", 0),
            testedFamilies = json.optInt("tested_families", 0),
            champions = json.optInt("champions", 0),
            challengers = json.optInt("challengers", 0),
            untestedFamilies = json.optInt("untested_families", 0),
            topFive = topFive,
            families = families,
            rankingNote = json.optString("ranking_note")
        )
    }

    suspend fun fetchResearchPlan(): Pair<ApiResult, ResearchPlan?> {
        val result = request("GET", "/research/plan", null)
        if (!result.ok) return result to null
        val json = JSONObject(result.body)

        fun parseCandidate(obj: JSONObject): ResearchCandidate =
            ResearchCandidate(
                candidateId = obj.optString("candidate_id"),
                lifecycleState = obj.optString("lifecycle_state", "RESEARCHING"),
                symbol = obj.optString("symbol").ifBlank { null },
                companyName = obj.optString("company_name"),
                listingDate = obj.optString("listing_date").ifBlank { null },
                issueStartDate = obj.optString("issue_start_date").ifBlank { null },
                issueEndDate = obj.optString("issue_end_date").ifBlank { null },
                officialIssueId = obj.optString("official_issue_id").ifBlank { null },
                isin = obj.optString("isin").ifBlank { null },
                board = obj.optString("board").ifBlank { null },
                isSme = obj.optBoolean("is_sme", false),
                issueStatus = obj.optString("issue_status").ifBlank { null },
                nseListingConfirmed = obj.optBoolean("nse_listing_confirmed", false),
                growwSymbol = obj.optString("groww_symbol").ifBlank { null },
                growwSeries = obj.optString("groww_series").ifBlank { null },
                buyAllowed = obj.optBoolean("buy_allowed", false),
                sellAllowed = obj.optBoolean("sell_allowed", false),
                symbolResolved = obj.optBoolean("symbol_resolved", false),
                growwResolutionStatus = obj.optString("groww_resolution_status", "UNKNOWN"),
                resolutionStatus = obj.optString("resolution_status", "UNKNOWN"),
                issuePriceText = obj.optString("issue_price_text").ifBlank { null },
                subscriptionMultiple = if (obj.isNull("subscription_multiple")) null else obj.optDouble("subscription_multiple"),
                tradingDayNumber = if (obj.isNull("trading_day_number")) null else obj.optInt("trading_day_number"),
                growwLotSize = if (obj.isNull("groww_lot_size")) null else obj.optInt("groww_lot_size")
            )

        fun parseArray(name: String): List<ResearchCandidate> {
            val arr = json.optJSONArray(name) ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    add(parseCandidate(arr.getJSONObject(i)))
                }
            }
        }

        val errorsJson = json.optJSONArray("errors")
        val errors = buildList {
            if (errorsJson != null) {
                for (i in 0 until errorsJson.length()) add(errorsJson.optString(i))
            }
        }

        return result to ResearchPlan(
            generatedAt = json.optString("generated_at").ifBlank { null },
            sourceReady = json.optBoolean("source_ready", false),
            researchHealth = json.optString("research_health", "UNKNOWN"),
            calendarReady = json.optBoolean("calendar_ready", false),
            nextTradingDay = json.optString("next_trading_day").ifBlank { null },
            candidateCount = json.optInt("candidate_count", 0),
            nseIdentityConfirmedCount = json.optInt("nse_identity_confirmed_count", 0),
            growwResolvedCount = json.optInt("groww_resolved_count", 0),
            growwPendingCount = json.optInt("groww_pending_count", 0),
            nextTradingDayCandidates = parseArray("next_trading_day_candidates"),
            weekCandidates = parseArray("week_candidates"),
            allKnownCandidates = parseArray("all_known_candidates"),
            errors = errors
        )
    }

    suspend fun refreshResearchPlan(): Pair<ApiResult, ResearchPlan?> {
        val result = request("POST", "/research/refresh", "{}")
        if (!result.ok) return result to null
        val json = JSONObject(result.body)

        fun parseCandidate(obj: JSONObject): ResearchCandidate =
            ResearchCandidate(
                candidateId = obj.optString("candidate_id"),
                lifecycleState = obj.optString("lifecycle_state", "RESEARCHING"),
                symbol = obj.optString("symbol").ifBlank { null },
                companyName = obj.optString("company_name"),
                listingDate = obj.optString("listing_date").ifBlank { null },
                issueStartDate = obj.optString("issue_start_date").ifBlank { null },
                issueEndDate = obj.optString("issue_end_date").ifBlank { null },
                officialIssueId = obj.optString("official_issue_id").ifBlank { null },
                isin = obj.optString("isin").ifBlank { null },
                board = obj.optString("board").ifBlank { null },
                isSme = obj.optBoolean("is_sme", false),
                issueStatus = obj.optString("issue_status").ifBlank { null },
                nseListingConfirmed = obj.optBoolean("nse_listing_confirmed", false),
                growwSymbol = obj.optString("groww_symbol").ifBlank { null },
                growwSeries = obj.optString("groww_series").ifBlank { null },
                buyAllowed = obj.optBoolean("buy_allowed", false),
                sellAllowed = obj.optBoolean("sell_allowed", false),
                symbolResolved = obj.optBoolean("symbol_resolved", false),
                growwResolutionStatus = obj.optString("groww_resolution_status", "UNKNOWN"),
                resolutionStatus = obj.optString("resolution_status", "UNKNOWN"),
                issuePriceText = obj.optString("issue_price_text").ifBlank { null },
                subscriptionMultiple = if (obj.isNull("subscription_multiple")) null else obj.optDouble("subscription_multiple"),
                tradingDayNumber = if (obj.isNull("trading_day_number")) null else obj.optInt("trading_day_number"),
                growwLotSize = if (obj.isNull("groww_lot_size")) null else obj.optInt("groww_lot_size")
            )

        fun parseArray(name: String): List<ResearchCandidate> {
            val arr = json.optJSONArray(name) ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) add(parseCandidate(arr.getJSONObject(i)))
            }
        }

        val errorsJson = json.optJSONArray("errors")
        val errors = buildList {
            if (errorsJson != null) {
                for (i in 0 until errorsJson.length()) add(errorsJson.optString(i))
            }
        }

        return result to ResearchPlan(
            generatedAt = json.optString("generated_at").ifBlank { null },
            sourceReady = json.optBoolean("source_ready", false),
            researchHealth = json.optString("research_health", "UNKNOWN"),
            calendarReady = json.optBoolean("calendar_ready", false),
            nextTradingDay = json.optString("next_trading_day").ifBlank { null },
            candidateCount = json.optInt("candidate_count", 0),
            nseIdentityConfirmedCount = json.optInt("nse_identity_confirmed_count", 0),
            growwResolvedCount = json.optInt("groww_resolved_count", 0),
            growwPendingCount = json.optInt("groww_pending_count", 0),
            nextTradingDayCandidates = parseArray("next_trading_day_candidates"),
            weekCandidates = parseArray("week_candidates"),
            allKnownCandidates = parseArray("all_known_candidates"),
            errors = errors
        )
    }

    suspend fun fetchResearchDashboard(): Pair<ApiResult, ResearchDashboard?> {
        val result = request("GET", "/research/dashboard", null)
        if (!result.ok) return result to null
        return try {
            val json = JSONObject(result.body)

            fun stringList(obj: JSONObject, name: String): List<String> {
                val arr = obj.optJSONArray(name) ?: return emptyList()
                return buildList {
                    for (i in 0 until arr.length()) {
                        val value = arr.optString(i)
                        if (value.isNotBlank()) add(value)
                    }
                }
            }

            fun candidate(obj: JSONObject): ResearchCandidate = ResearchCandidate(
                candidateId = obj.optString("candidate_id"),
                lifecycleState = obj.optString("lifecycle_state", "RESEARCHING"),
                symbol = obj.optString("symbol").ifBlank { null },
                companyName = obj.optString("company_name"),
                listingDate = obj.optString("listing_date").ifBlank { null },
                issueStartDate = obj.optString("issue_start_date").ifBlank { null },
                issueEndDate = obj.optString("issue_end_date").ifBlank { null },
                officialIssueId = obj.optString("official_issue_id").ifBlank { null },
                isin = obj.optString("isin").ifBlank { null },
                board = obj.optString("board").ifBlank { null },
                isSme = obj.optBoolean("is_sme", false),
                issueStatus = obj.optString("issue_status").ifBlank { null },
                nseListingConfirmed = obj.optBoolean("nse_listing_confirmed", false),
                growwSymbol = obj.optString("groww_symbol").ifBlank { null },
                growwSeries = obj.optString("groww_series").ifBlank { null },
                buyAllowed = obj.optBoolean("buy_allowed", false),
                sellAllowed = obj.optBoolean("sell_allowed", false),
                symbolResolved = obj.optBoolean("symbol_resolved", false),
                growwResolutionStatus = obj.optString("groww_resolution_status", "UNKNOWN"),
                resolutionStatus = obj.optString("resolution_status", "UNKNOWN"),
                issuePriceText = obj.optString("issue_price_text").ifBlank { null },
                subscriptionMultiple = if (obj.isNull("subscription_multiple")) null else obj.optDouble("subscription_multiple"),
                tradingDayNumber = if (obj.isNull("trading_day_number")) null else obj.optInt("trading_day_number"),
                growwLotSize = if (obj.isNull("groww_lot_size")) null else obj.optInt("groww_lot_size")
            )

            fun signal(obj: JSONObject): OpportunitySignal = OpportunitySignal(
                symbol = obj.optString("symbol"),
                companyName = obj.optString("company_name").ifBlank { obj.optString("symbol") },
                direction = obj.optString("direction"),
                action = obj.optString("action"),
                score = obj.optDouble("score", 0.0),
                confidence = obj.optDouble("confidence", 0.0),
                entryPrice = obj.optDouble("entry_price", 0.0),
                stopLoss = obj.optDouble("stop_loss", 0.0),
                target1 = obj.optDouble("target1", 0.0),
                target2 = obj.optDouble("target2", 0.0),
                quantity = obj.optInt("quantity", 0),
                expectedNetAtTarget2 = obj.optDouble("expected_net_at_target2", 0.0),
                minimumNetEdgeRupees = obj.optDouble("minimum_net_edge_rupees", 0.0),
                relativeVolume = obj.optDouble("relative_volume", 0.0),
                spreadBps = obj.optDouble("spread_bps", 0.0),
                tradingDayNumber = obj.optInt("trading_day_number", 0),
                isSme = obj.optBoolean("is_sme", false),
                reasonCodes = stringList(obj, "reason_codes")
            )

            fun pick(obj: JSONObject): ResearchPick = ResearchPick(
                symbol = obj.optString("symbol").ifBlank { null },
                companyName = obj.optString("company_name").ifBlank { obj.optString("symbol", "Unknown") },
                direction = obj.optString("direction").ifBlank { null },
                researchScore = if (obj.has("research_score")) obj.optDouble("research_score") else obj.optDouble("score", 0.0),
                preMarketBias = obj.optString("pre_market_bias").ifBlank { null },
                entryPrice = if (obj.has("entry_price")) obj.optDouble("entry_price") else null,
                stopLoss = if (obj.has("stop_loss")) obj.optDouble("stop_loss") else null,
                target1 = if (obj.has("target1")) obj.optDouble("target1") else null,
                target2 = if (obj.has("target2")) obj.optDouble("target2") else null,
                quantity = if (obj.has("quantity")) obj.optInt("quantity") else null,
                confidence = if (obj.has("confidence")) obj.optDouble("confidence") else null,
                tradingDayNumber = if (obj.has("trading_day_number") && !obj.isNull("trading_day_number")) obj.optInt("trading_day_number") else null,
                board = obj.optString("board").ifBlank { if (obj.optBoolean("is_sme", false)) "SME" else null },
                reasons = if (obj.has("reason_codes")) stringList(obj, "reason_codes") else stringList(obj, "reasons"),
                note = obj.optString("note").ifBlank { null }
            )

            fun candidateArray(name: String): List<ResearchCandidate> {
                val arr = json.optJSONArray(name) ?: return emptyList()
                return buildList { for (i in 0 until arr.length()) add(candidate(arr.getJSONObject(i))) }
            }

            val signalsArray = json.optJSONArray("active_signals")
            val signals = buildList {
                if (signalsArray != null) for (i in 0 until signalsArray.length()) add(signal(signalsArray.getJSONObject(i)))
            }
            val topArray = json.optJSONArray("top_three")
            val top = buildList {
                if (topArray != null) for (i in 0 until topArray.length()) add(pick(topArray.getJSONObject(i)))
            }
            val positionsArray = json.optJSONArray("open_positions")
            val positions = buildList {
                if (positionsArray != null) for (i in 0 until positionsArray.length()) {
                    val obj = positionsArray.getJSONObject(i)
                    add(
                        OwnedIpoPosition(
                            symbol = obj.optString("symbol"),
                            quantity = obj.optInt("quantity", 0),
                            avgPrice = obj.optDouble("avg_price", 0.0),
                            mark = obj.optDouble("mark", 0.0),
                            unrealizedPnl = obj.optDouble("unrealized_pnl", 0.0)
                        )
                    )
                }
            }
            val closedArray = json.optJSONArray("closed_calls")
            val closed = buildList {
                if (closedArray != null) for (i in 0 until closedArray.length()) {
                    val obj = closedArray.getJSONObject(i)
                    add(
                        ClosedIpoCall(
                            symbol = obj.optString("symbol"),
                            direction = obj.optString("direction"),
                            quantity = obj.optInt("quantity", 0),
                            entryTimestamp = obj.optString("entry_timestamp").ifBlank { null },
                            exitTimestamp = obj.optString("exit_timestamp").ifBlank { null },
                            entryPrice = obj.optDouble("entry_price", 0.0),
                            exitPrice = obj.optDouble("exit_price", 0.0),
                            netPnl = obj.optDouble("net_pnl", 0.0),
                            outcome = obj.optString("outcome", "FLAT")
                        )
                    )
                }
            }

            fun learningArray(kind: String): List<LearningRecord> {
                val learning = json.optJSONObject("learning") ?: return emptyList()
                val arr = learning.optJSONArray(kind) ?: return emptyList()
                return buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val summary = when (obj.optString("type")) {
                            "WEEKLY_STRATEGY_REVALIDATION" ->
                                "Champions retained: " + (obj.optJSONArray("retained_champions")?.length() ?: 0) +
                                    " • Weak families flagged: " + (obj.optJSONArray("demotion_candidates")?.length() ?: 0)
                            else ->
                                "Closed " + obj.optInt("closed_calls", 0) +
                                    " • W " + obj.optInt("wins", 0) +
                                    " / L " + obj.optInt("losses", 0) +
                                    " • P&L ₹" + String.format("%.2f", obj.optDouble("realized_net_pnl", 0.0)) +
                                    " • Active D1-D30 " + obj.optInt("active_30d", 0)
                        }
                        add(LearningRecord(obj.optString("at").ifBlank { null }, obj.optString("type", kind.uppercase()), summary))
                    }
                }
            }

            val goal = json.optJSONObject("daily_goal") ?: JSONObject()
            val coverage = json.optJSONObject("coverage") ?: JSONObject()
            result to ResearchDashboard(
                generatedAt = json.optString("generated_at").ifBlank { null },
                dailyGoal = DailyGoalStatus(
                    date = goal.optString("date"),
                    targetRupees = goal.optDouble("target_rupees", 5000.0),
                    realizedNetPnl = goal.optDouble("realized_net_pnl", 0.0),
                    remainingRupees = goal.optDouble("remaining_rupees", 5000.0),
                    targetAchieved = goal.optBoolean("target_achieved", false),
                    returnOnBudgetPct = goal.optDouble("return_on_budget_pct", 0.0),
                    closedCalls = goal.optInt("closed_calls", 0),
                    wins = goal.optInt("wins", 0),
                    losses = goal.optInt("losses", 0)
                ),
                activeSignals = signals,
                topThree = top,
                tomorrowCandidates = candidateArray("tomorrow_candidates"),
                active30dCandidates = candidateArray("active_30d_candidates"),
                openPositions = positions,
                closedCalls = closed,
                dailyLearning = learningArray("daily"),
                weeklyLearning = learningArray("weekly"),
                mainboardCoverage = coverage.optInt("mainboard", 0),
                smeCoverage = coverage.optInt("sme", 0),
                coverageRule = coverage.optString("rule")
            )
        } catch (exc: Exception) {
            ApiResult(false, 0, result.body, "Research dashboard parse failed: " + (exc.message ?: exc::class.java.simpleName)) to null
        }
    }

    suspend fun manualCardOrder(
        symbol: String,
        action: String,
        fraction: Double = 1.0
    ): ApiResult {
        val payload = JSONObject()
            .put("symbol", symbol.trim().uppercase())
            .put("action", action.trim().uppercase())
            .put("fraction", fraction.coerceIn(0.01, 1.0))
            .toString()
        return request("POST", "/orders/manual", payload)
    }

    suspend fun fetchLiveState(): Pair<ApiResult, LiveStateStatus?> {
        val result = request("GET", "/live/state", null)
        if (!result.ok) return result to null
        val json = JSONObject(result.body)
        return result to LiveStateStatus(
            enabled = json.optBoolean("enabled", false),
            budgetRupees = json.optInt("budget_rupees", 100_000),
            updatedAt = json.optString("updated_at").ifBlank { null },
            eventId = json.optLong("event_id", 0L)
        )
    }

    suspend fun setLiveState(enabled: Boolean, budgetRupees: Int): Pair<ApiResult, LiveStateStatus?> {
        val payload = JSONObject()
            .put("enabled", enabled)
            .put("budget_rupees", budgetRupees)
            .toString()
        val result = request("POST", "/live/state", payload)
        if (!result.ok) return result to null
        val json = JSONObject(result.body)
        return result to LiveStateStatus(
            enabled = json.optBoolean("enabled", false),
            budgetRupees = json.optInt("budget_rupees", budgetRupees),
            updatedAt = json.optString("updated_at").ifBlank { null },
            eventId = json.optLong("event_id", 0L)
        )
    }

    suspend fun fetchOrderEvents(afterId: Long): Pair<ApiResult, OrderEventBatch?> {
        val result = request("GET", "/events/orders?after_id=" + afterId + "&limit=100", null)
        if (!result.ok) return result to null
        val json = JSONObject(result.body)
        val arr = json.optJSONArray("events")
        val events = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        OrderLifecycleEvent(
                            id = obj.optLong("id", 0L),
                            timestamp = obj.optString("timestamp"),
                            eventType = obj.optString("event_type"),
                            symbol = obj.optString("symbol").ifBlank { null },
                            side = obj.optString("side").ifBlank { null },
                            quantity = if (obj.isNull("quantity")) null else obj.optInt("quantity"),
                            price = if (obj.isNull("price")) null else obj.optDouble("price"),
                            orderId = obj.optString("order_id").ifBlank { null },
                            message = obj.optString("message").ifBlank { null }
                        )
                    )
                }
            }
        }
        return result to OrderEventBatch(
            events = events,
            lastId = json.optLong("last_id", afterId)
        )
    }

    suspend fun exportAudit(days: Int = 7): ApiResult {
        return request("GET", "/audit/export?days=" + days.coerceIn(1, 31), null)
    }

    suspend fun validate(): Pair<ApiResult, ValidationStatus?> {
        val result = request("POST", "/settings/validate", "{}")
        if (!result.ok) return result to null
        val json = JSONObject(result.body)
        return result to ValidationStatus(
            growwAuthOk = json.optBoolean("groww_auth_ok", false),
            detectedEgressIp = json.optString("detected_egress_ip").ifBlank { null },
            expectedStaticIp = json.optString("expected_static_ip").ifBlank { null },
            staticIpMatches = json.optBoolean("static_ip_matches", false),
            staticIpConfirmed = json.optBoolean("static_ip_confirmed", false),
            secretStoreReady = json.optBoolean("secret_store_ready", false),
            calendarReady = json.optBoolean("calendar_ready", false),
            nseIdentitySourceReady = json.optBoolean("nse_identity_source_ready", false),
            liveExecutionReady = json.optBoolean("live_execution_ready", false),
            growwError = json.optString("groww_error").ifBlank { null },
            egressError = json.optString("egress_error").ifBlank { null }
        )
    }

    private suspend fun request(method: String, path: String, body: String?): ApiResult =
        withContext(Dispatchers.IO) {
            // The legacy remote control plane is intentionally disabled in the direct
            // Android build. Broker authentication is handled by DirectGrowwClient.
            ApiResult(
                ok = false,
                statusCode = 0,
                body = "",
                error = "This legacy remote feature is not used in direct device mode."
            )
        }
}
