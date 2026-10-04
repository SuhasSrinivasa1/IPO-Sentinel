package com.suhas.iposentinel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Direct, read-only IPO research path for the Android client.
 *
 * This client intentionally does not place orders. It builds the IPO research
 * universe directly from official NSE sources and resolves final NSE symbols
 * against Groww's public NSE/CASH instrument master.
 */
class DirectResearchClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun refreshPlan(force: Boolean = false): Pair<ApiResult, ResearchPlan?> =
        withContext(Dispatchers.IO) {
            val cached = loadCachedPlan()
            val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
            if (!force && cached != null && System.currentTimeMillis() - savedAt < CACHE_TTL_MS) {
                return@withContext ApiResult(true, 200, "{}", null) to cached
            }

            try {
                val plan = buildPlan(cached)
                savePlan(plan)
                ApiResult(true, 200, "{}", null) to plan
            } catch (error: Exception) {
                if (cached != null) {
                    val degraded = cached.copy(
                        researchHealth = "DEGRADED",
                        errors = (cached.errors + "DIRECT_REFRESH:" + safeMessage(error)).distinct()
                    )
                    return@withContext ApiResult(true, 200, "{}", null) to degraded
                }
                ApiResult(false, 0, "", "Direct IPO research failed: " + safeMessage(error)) to null
            }
        }

    suspend fun fetchDashboard(force: Boolean = false): Pair<ApiResult, ResearchDashboard?> {
        val (result, plan) = refreshPlan(force)
        if (!result.ok || plan == null) return result to null

        val active30 = plan.allKnownCandidates
            .filter { it.nseListingConfirmed && (it.tradingDayNumber ?: 0) in 1..30 }
            .sortedWith(compareBy<ResearchCandidate> { it.tradingDayNumber ?: 99 }.thenBy { it.symbol ?: "" })

        val rankedPool = (plan.nextTradingDayCandidates + active30)
            .distinctBy { it.candidateId }

        val ranked = rankedPool
            .map { rankCandidate(it, plan.nextTradingDay) }
            .sortedByDescending { it.researchScore }
            .take(3)

        val dashboard = ResearchDashboard(
            generatedAt = plan.generatedAt,
            dailyGoal = DailyGoalStatus(
                date = LocalDate.now(IST).toString(),
                targetRupees = 5000.0,
                realizedNetPnl = 0.0,
                remainingRupees = 5000.0,
                targetAchieved = false
            ),
            activeSignals = emptyList(),
            topThree = ranked,
            tomorrowCandidates = plan.nextTradingDayCandidates,
            active30dCandidates = active30,
            openPositions = emptyList(),
            closedCalls = emptyList(),
            dailyLearning = emptyList(),
            weeklyLearning = emptyList(),
            mainboardCoverage = (plan.nextTradingDayCandidates + active30).count { !it.isSme },
            smeCoverage = (plan.nextTradingDayCandidates + active30).count { it.isSme },
            coverageRule = "Direct research is read-only: official NSE identity + exact Groww NSE/CASH instrument resolution. Live price/depth signals and order execution are not enabled in this build."
        )
        return ApiResult(true, 200, "{}", null) to dashboard
    }

    fun cachedPlan(): ResearchPlan? = loadCachedPlan()

    private fun buildPlan(priorPlan: ResearchPlan?): ResearchPlan {
        val now = ZonedDateTime.now(IST)
        val today = now.toLocalDate()
        val errors = mutableListOf<String>()
        val session = NseSession()

        var calendarReady = false
        var holidays = emptySet<LocalDate>()
        var issueSourceReady = false
        val rawIssues = mutableListOf<RawIssue>()

        try {
            try {
                holidays = parseHolidays(session.json(NSE_HOLIDAYS))
                calendarReady = holidays.isNotEmpty()
                if (!calendarReady) errors += "NSE_HOLIDAY_SOURCE:EMPTY"
            } catch (error: Exception) {
                errors += "NSE_HOLIDAY_SOURCE:" + error.javaClass.simpleName
            }

            for ((path, source) in listOf(
                NSE_UPCOMING to "NSE_UPCOMING_ISSUES",
                NSE_CURRENT to "NSE_CURRENT_ISSUES"
            )) {
                try {
                    val rows = extractIssueRecords(session.json(path))
                    issueSourceReady = true
                    rows.forEach { mergeResearch(rawIssues, it, source) }
                } catch (error: Exception) {
                    errors += source + ":" + error.javaClass.simpleName
                }
            }

            try {
                val finalRows = extractForthcomingRecords(session.json(NSE_FORTHCOMING))
                issueSourceReady = true
                finalRows.forEach { mergeFinalIdentity(rawIssues, it, "NSE_FORTHCOMING_LISTING") }
            } catch (error: Exception) {
                errors += "NSE_FORTHCOMING_LISTING:" + error.javaClass.simpleName
            }

            try {
                val cutoff = today.minusDays(50)
                val recentRows = extractForthcomingRecords(session.json(NSE_RECENT))
                    .filter { row ->
                        parseDate(first(row, "listingDate", "dateOfListing", "date_of_listing", "date", "listing_date"))
                            ?.let { day -> !day.isBefore(cutoff) } == true
                    }
                issueSourceReady = true
                recentRows.forEach { appendIfNew(rawIssues, it, "NSE_RECENT_LISTING") }
            } catch (error: Exception) {
                errors += "NSE_RECENT_LISTING:" + error.javaClass.simpleName
            }
        } finally {
            session.close()
        }

        val normalized = rawIssues.map { normalizeCandidate(it.obj, it.source) }

        val officialSymbols = normalized
            .filter { it.source in FINAL_IDENTITY_SOURCES && it.symbol != null && it.listingDate != null }
            .mapNotNull { it.symbol }
            .toSet()
        val officialIsins = normalized.mapNotNull { it.isin }.toSet()

        val instrumentRows = try {
            if (officialSymbols.isEmpty()) emptyList() else loadGrowwInstrumentRows(officialSymbols, officialIsins)
        } catch (error: Exception) {
            errors += "GROWW_INSTRUMENT_MASTER:" + error.javaClass.simpleName
            emptyList()
        }

        val nextTradingDay = if (calendarReady) {
            nextTradingDay(today, holidays)
        } else {
            nextWeekday(today)
        }
        val weekEnd = nextTradingDay.plusDays(6)

        val candidates = normalized.map { item ->
            val finalIdentity = item.source in FINAL_IDENTITY_SOURCES &&
                item.symbol != null && item.listingDate != null

            val resolution = if (finalIdentity) {
                resolveInstrument(instrumentRows, item.symbol, item.isin)
            } else {
                InstrumentResolution("WAIT_NSE_IDENTITY", null)
            }

            val tradingDayNumber = if (
                calendarReady && item.listingDate != null && !item.listingDate.isAfter(today)
            ) {
                tradingDayNumber(item.listingDate, today, holidays)
            } else null

            val lifecycle: String
            val resolutionStatus: String
            when {
                item.symbol == null -> {
                    lifecycle = "RESEARCHED_NO_SYMBOL"
                    resolutionStatus = "RESEARCH_CONTINUES_SYMBOL_PENDING"
                }
                item.listingDate == null -> {
                    lifecycle = "RESEARCHING"
                    resolutionStatus = "WAIT_OFFICIAL_LISTING_DATE"
                }
                !finalIdentity -> {
                    lifecycle = "LISTING_DATE_CONFIRMED"
                    resolutionStatus = "WAIT_NSE_IDENTITY_CONFIRMATION"
                }
                resolution.status != "RESOLVED" -> {
                    lifecycle = "GROWW_INSTRUMENT_PENDING"
                    resolutionStatus = resolution.status
                }
                item.listingDate == today -> {
                    lifecycle = "LISTING_DAY_WATCH"
                    resolutionStatus = "WAIT_LISTING_SESSION_AND_LIVE_DATA"
                }
                item.listingDate.isBefore(today) -> {
                    if (tradingDayNumber == null) {
                        lifecycle = "D1_D30_MONITOR"
                        resolutionStatus = "WAIT_OFFICIAL_TRADING_DAY_COUNT"
                    } else if (tradingDayNumber <= 30) {
                        lifecycle = "D1_D30_MONITOR"
                        resolutionStatus = "POST_LISTING_MONITOR_D$tradingDayNumber"
                    } else {
                        lifecycle = "COMPLETE"
                        resolutionStatus = "D30_WINDOW_COMPLETE"
                    }
                }
                else -> {
                    lifecycle = "GROWW_INSTRUMENT_RESOLVED"
                    resolutionStatus = "RESOLVED_PRE_LISTING"
                }
            }

            val row = resolution.row
            ResearchCandidate(
                candidateId = item.candidateId,
                lifecycleState = lifecycle,
                symbol = item.symbol,
                companyName = item.companyName,
                listingDate = item.listingDate?.toString(),
                issueStartDate = item.issueStartDate?.toString(),
                issueEndDate = item.issueEndDate?.toString(),
                officialIssueId = item.officialIssueId,
                isin = item.isin,
                board = item.board,
                isSme = item.isSme,
                issueStatus = item.issueStatus,
                nseListingConfirmed = finalIdentity,
                growwSymbol = row?.get("groww_symbol")?.takeIf { it.isNotBlank() },
                growwSeries = row?.get("series")?.takeIf { it.isNotBlank() },
                buyAllowed = allowed(row?.get("buy_allowed")),
                sellAllowed = allowed(row?.get("sell_allowed")),
                symbolResolved = finalIdentity && resolution.status == "RESOLVED",
                growwResolutionStatus = resolution.status,
                resolutionStatus = resolutionStatus,
                issuePriceText = item.issuePriceText,
                subscriptionMultiple = item.subscriptionMultiple,
                tradingDayNumber = tradingDayNumber,
                growwLotSize = row?.get("lot_size")?.toDoubleOrNull()?.toInt()
            )
        }.toMutableList()

        priorPlan?.allKnownCandidates.orEmpty().forEach { saved ->
            if (candidates.any { it.candidateId == saved.candidateId }) return@forEach
            val listing = saved.listingDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val issueEnd = saved.issueEndDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val anchor = listing ?: issueEnd ?: return@forEach
            if (anchor.isBefore(today.minusDays(60)) || anchor.isAfter(today.plusDays(60))) return@forEach

            val dayNo = if (calendarReady && listing != null && !listing.isAfter(today)) {
                tradingDayNumber(listing, today, holidays)
            } else saved.tradingDayNumber

            val restored = if (dayNo != null && dayNo > 30) {
                saved.copy(
                    tradingDayNumber = dayNo,
                    lifecycleState = "COMPLETE",
                    resolutionStatus = "D30_WINDOW_COMPLETE"
                )
            } else if (dayNo != null && dayNo >= 1) {
                saved.copy(
                    tradingDayNumber = dayNo,
                    lifecycleState = "D1_D30_MONITOR",
                    resolutionStatus = "POST_LISTING_MONITOR_D$dayNo"
                )
            } else saved
            candidates += restored
        }

        val unique = candidates.distinctBy { it.candidateId }
        val nextCandidates = unique.filter { it.listingDate == nextTradingDay.toString() }
        val weekCandidates = unique
            .filter {
                val day = it.listingDate?.let { text -> runCatching { LocalDate.parse(text) }.getOrNull() }
                day != null && !day.isBefore(nextTradingDay) && !day.isAfter(weekEnd)
            }
            .sortedWith(compareBy<ResearchCandidate> { it.listingDate ?: "" }.thenBy { it.symbol ?: "" }.thenBy { it.companyName })

        val health = when {
            !issueSourceReady -> "FAILED"
            errors.isNotEmpty() -> "DEGRADED"
            else -> "OK"
        }

        return ResearchPlan(
            generatedAt = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            sourceReady = issueSourceReady,
            researchHealth = health,
            calendarReady = calendarReady,
            nextTradingDay = nextTradingDay.toString(),
            candidateCount = unique.size,
            nseIdentityConfirmedCount = unique.count { it.nseListingConfirmed },
            growwResolvedCount = unique.count { it.symbolResolved },
            growwPendingCount = unique.count { it.nseListingConfirmed && !it.symbolResolved },
            nextTradingDayCandidates = nextCandidates,
            weekCandidates = weekCandidates,
            allKnownCandidates = unique,
            errors = errors.distinct()
        )
    }

    private fun rankCandidate(candidate: ResearchCandidate, nextTradingDay: String?): ResearchPick {
        var score = 40.0
        val reasons = mutableListOf<String>()

        if (candidate.nseListingConfirmed) {
            score += 15.0
            reasons += "OFFICIAL_NSE_IDENTITY"
        }
        if (candidate.symbolResolved) {
            score += 15.0
            reasons += "EXACT_GROWW_INSTRUMENT"
        }
        val sub = candidate.subscriptionMultiple
        when {
            sub == null -> Unit
            sub >= 10.0 -> {
                score += 14.0
                reasons += "VERY_STRONG_SUBSCRIPTION"
            }
            sub >= 3.0 -> {
                score += 9.0
                reasons += "STRONG_SUBSCRIPTION"
            }
            sub >= 1.0 -> {
                score += 4.0
                reasons += "FULLY_SUBSCRIBED"
            }
            else -> {
                score -= 6.0
                reasons += "WEAK_SUBSCRIPTION"
            }
        }
        if (candidate.isSme) {
            score -= 3.0
            reasons += "SME_LIQUIDITY_RISK"
        } else {
            score += 3.0
            reasons += "MAINBOARD"
        }
        if (nextTradingDay != null && candidate.listingDate == nextTradingDay) {
            score += 8.0
            reasons += "NEXT_LISTING_DAY"
        }
        when (candidate.tradingDayNumber ?: 0) {
            in 1..5 -> {
                score += 5.0
                reasons += "EARLY_POST_LISTING_WINDOW"
            }
            in 6..30 -> {
                score += 3.0
                reasons += "ACTIVE_D1_D30_WINDOW"
            }
        }
        score = score.coerceIn(0.0, 100.0)

        return ResearchPick(
            symbol = candidate.symbol,
            companyName = candidate.companyName,
            direction = null,
            researchScore = score,
            preMarketBias = if (score >= 68.0) "WATCH_LONG" else "WAIT_LIVE_CONFIRMATION",
            entryPrice = null,
            stopLoss = null,
            target1 = null,
            target2 = null,
            quantity = null,
            confidence = null,
            tradingDayNumber = candidate.tradingDayNumber,
            board = if (candidate.isSme) "SME" else "MAINBOARD",
            reasons = reasons,
            note = "Research ranking only; no trade direction without live price/volume/order-book confirmation."
        )
    }

    private fun normalizeCandidate(obj: JSONObject, source: String): NormalizedCandidate {
        val symbol = firstString(obj, "symbol", "trading_symbol", "issue_symbol", "tradingSymbol", "securitySymbol")
            ?.uppercase(Locale.ENGLISH)
        val company = firstString(obj, "companyName", "company", "issuerName", "securityName", "name")
            ?: symbol
            ?: "Unknown issuer"
        val listing = parseDate(first(obj, "listingDate", "dateOfListing", "date_of_listing", "listing_date", "tentativeListingDate", "date"))
        val start = parseDate(first(obj, "issueStartDate", "startDate", "openDate", "issueOpenDate"))
        val end = parseDate(first(obj, "issueEndDate", "endDate", "closeDate", "issueCloseDate"))
        val isin = firstString(obj, "isin", "isinCode")?.uppercase(Locale.ENGLISH)
        val board = firstString(obj, "series", "board", "category", "issueType")
        val subscription = firstString(obj, "noOfTime", "subscriptionMultiple", "subscription")
            ?.replace(",", "")
            ?.toDoubleOrNull()

        return NormalizedCandidate(
            candidateId = candidateIdentity(obj)
                ?: "RESEARCH:" + sha256(normalizeCompany(company) + "|" + (start?.toString() ?: "") + "|" + (end?.toString() ?: "") + "|" + (symbol ?: "")).take(20),
            source = source,
            symbol = symbol,
            companyName = company,
            listingDate = listing,
            issueStartDate = start,
            issueEndDate = end,
            officialIssueId = firstString(obj, "issueId", "issue_id", "issueIdentifier", "issueCode", "offerId", "offerDocumentId"),
            isin = isin,
            board = board,
            isSme = board?.uppercase(Locale.ENGLISH) in setOf("SME", "ST", "SM"),
            issueStatus = firstString(obj, "status", "issueStatus"),
            issuePriceText = firstString(obj, "issuePrice", "priceBand"),
            subscriptionMultiple = subscription
        )
    }

    private fun extractIssueRecords(root: Any?): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        walkJson(root) { obj ->
            val hasCompany = listOf("companyName", "company", "issuerName", "securityName")
                .any { first(obj, it) != null }
            val hasFact = listOf(
                "symbol", "tradingSymbol", "issue_symbol", "issueStartDate", "issueEndDate",
                "listingDate", "tentativeListingDate", "dateOfListing", "issuePrice", "issueSize", "status"
            ).any { first(obj, it) != null }
            if (hasCompany && hasFact && candidateIdentity(obj) != null) out += JSONObject(obj.toString())
        }
        return out.distinctBy { candidateIdentity(it) ?: it.toString() }
    }

    private fun extractForthcomingRecords(root: Any?): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        walkJson(root) { obj ->
            val symbol = firstString(obj, "symbol", "tradingSymbol", "securitySymbol")
            val listing = parseDate(first(obj, "listingDate", "dateOfListing", "date_of_listing", "date", "listing_date"))
            val isin = firstString(obj, "isin", "isinCode")
            if (!symbol.isNullOrBlank() && (listing != null || !isin.isNullOrBlank())) {
                out += JSONObject(obj.toString())
            }
        }
        return out.distinctBy { firstString(it, "symbol", "tradingSymbol", "securitySymbol")?.uppercase(Locale.ENGLISH) ?: it.toString() }
    }

    private fun mergeResearch(list: MutableList<RawIssue>, item: JSONObject, source: String) {
        val key = candidateIdentity(item) ?: return
        val index = list.indexOfFirst { candidateIdentity(it.obj) == key }
        if (index >= 0) list[index] = RawIssue(mergeJson(list[index].obj, item), source)
        else list += RawIssue(JSONObject(item.toString()), source)
    }

    private fun mergeFinalIdentity(list: MutableList<RawIssue>, item: JSONObject, source: String) {
        val finalSymbol = firstString(item, "symbol", "tradingSymbol", "securitySymbol")?.uppercase(Locale.ENGLISH)
        val finalIsin = firstString(item, "isin", "isinCode")?.uppercase(Locale.ENGLISH)
        val finalCompany = normalizeCompany(firstString(item, "companyName", "company", "issuerName", "securityName", "name"))

        val matches = list.indices.filter { index ->
            val prior = list[index].obj
            val priorSymbol = firstString(prior, "symbol", "trading_symbol", "issue_symbol", "tradingSymbol", "securitySymbol")?.uppercase(Locale.ENGLISH)
            val priorIsin = firstString(prior, "isin", "isinCode")?.uppercase(Locale.ENGLISH)
            val priorCompany = normalizeCompany(firstString(prior, "companyName", "company", "issuerName", "securityName", "name"))
            (finalIsin != null && priorIsin != null && finalIsin == priorIsin) ||
                (finalSymbol != null && priorSymbol != null && finalSymbol == priorSymbol) ||
                (finalCompany.isNotBlank() && priorCompany.isNotBlank() && finalCompany == priorCompany)
        }

        if (matches.size == 1) {
            val index = matches.first()
            list[index] = RawIssue(mergeJson(list[index].obj, item), source)
        } else appendIfNew(list, item, source)
    }

    private fun appendIfNew(list: MutableList<RawIssue>, item: JSONObject, source: String) {
        val symbol = firstString(item, "symbol", "tradingSymbol", "securitySymbol")?.uppercase(Locale.ENGLISH).orEmpty()
        val isin = firstString(item, "isin", "isinCode")?.uppercase(Locale.ENGLISH).orEmpty()
        val company = normalizeCompany(firstString(item, "companyName", "company", "issuerName", "securityName", "name"))
        val duplicate = list.any { prior ->
            val pSymbol = firstString(prior.obj, "symbol", "tradingSymbol", "securitySymbol")?.uppercase(Locale.ENGLISH).orEmpty()
            val pIsin = firstString(prior.obj, "isin", "isinCode")?.uppercase(Locale.ENGLISH).orEmpty()
            val pCompany = normalizeCompany(firstString(prior.obj, "companyName", "company", "issuerName", "securityName", "name"))
            (symbol.isNotBlank() && symbol == pSymbol) ||
                (isin.isNotBlank() && isin == pIsin) ||
                (company.isNotBlank() && company == pCompany)
        }
        if (!duplicate) list += RawIssue(JSONObject(item.toString()), source)
    }

    private fun parseHolidays(root: Any?): Set<LocalDate> {
        val obj = root as? JSONObject ?: return emptySet()
        val cm = obj.optJSONArray("CM") ?: return emptySet()
        return buildSet {
            for (i in 0 until cm.length()) {
                val row = cm.optJSONObject(i) ?: continue
                parseDate(first(row, "tradingDate", "date", "holidayDate"))?.let { add(it) }
            }
        }
    }

    private fun loadGrowwInstrumentRows(symbols: Set<String>, isins: Set<String>): List<Map<String, String>> {
        val connection = (URL(GROWW_INSTRUMENT_CSV).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/csv,*/*")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("Groww instrument master returned HTTP $code")
        }

        val rows = mutableListOf<Map<String, String>>()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val headerLine = reader.readLine() ?: return@use
            val headers = parseCsvLine(headerLine).map { it.trim() }
            reader.forEachLine { line ->
                val values = parseCsvLine(line)
                if (values.size < headers.size) return@forEachLine
                val map = headers.indices.associate { index -> headers[index] to values.getOrElse(index) { "" } }
                if (!map["exchange"].equals("NSE", true) || !map["segment"].equals("CASH", true)) return@forEachLine
                val symbol = map["trading_symbol"]?.trim()?.uppercase(Locale.ENGLISH).orEmpty()
                val isin = map["isin"]?.trim()?.uppercase(Locale.ENGLISH).orEmpty()
                if (symbol in symbols || (isin.isNotBlank() && isin in isins)) rows += map
            }
        }
        connection.disconnect()
        return rows
    }

    private fun resolveInstrument(
        rows: List<Map<String, String>>,
        officialSymbol: String?,
        officialIsin: String?
    ): InstrumentResolution {
        val symbol = officialSymbol?.uppercase(Locale.ENGLISH)?.trim().orEmpty()
        val isin = officialIsin?.uppercase(Locale.ENGLISH)?.trim().orEmpty()
        if (symbol.isBlank()) return InstrumentResolution("WAIT_NSE_IDENTITY", null)

        val bySymbol = rows.filter { it["trading_symbol"]?.uppercase(Locale.ENGLISH)?.trim() == symbol }
        if (isin.isNotBlank()) {
            val byIsin = rows.filter { it["isin"]?.uppercase(Locale.ENGLISH)?.trim() == isin }
            val exact = bySymbol.filter { it["isin"]?.uppercase(Locale.ENGLISH)?.trim() == isin }
            return when {
                exact.size > 1 -> InstrumentResolution("BLOCK_MULTIPLE_EXACT_ROWS", null)
                exact.size == 1 -> InstrumentResolution("RESOLVED", exact.first())
                byIsin.isNotEmpty() || bySymbol.isNotEmpty() -> InstrumentResolution("BLOCK_IDENTIFIER_DISAGREEMENT", null)
                else -> InstrumentResolution("WAIT_GROWW_INSTRUMENT", null)
            }
        }
        return when {
            bySymbol.size > 1 -> InstrumentResolution("BLOCK_MULTIPLE_SYMBOL_ROWS", null)
            bySymbol.size == 1 -> InstrumentResolution("RESOLVED", bySymbol.first())
            else -> InstrumentResolution("WAIT_GROWW_INSTRUMENT", null)
        }
    }

    private fun nextTradingDay(today: LocalDate, holidays: Set<LocalDate>): LocalDate {
        var day = today.plusDays(1)
        repeat(15) {
            if (day.dayOfWeek.value <= 5 && day !in holidays) return day
            day = day.plusDays(1)
        }
        return nextWeekday(today)
    }

    private fun nextWeekday(today: LocalDate): LocalDate {
        var day = today.plusDays(1)
        while (day.dayOfWeek.value > 5) day = day.plusDays(1)
        return day
    }

    private fun tradingDayNumber(listing: LocalDate, today: LocalDate, holidays: Set<LocalDate>): Int {
        var cursor = listing
        var count = 0
        while (!cursor.isAfter(today)) {
            if (cursor.dayOfWeek.value <= 5 && cursor !in holidays) count += 1
            cursor = cursor.plusDays(1)
        }
        return count
    }

    private fun candidateIdentity(obj: JSONObject): String? {
        firstString(obj, "issueId", "issue_id", "issueIdentifier", "issueCode", "offerId", "offerDocumentId")
            ?.let { return "ISSUE:$it" }
        firstString(obj, "isin", "isinCode")?.uppercase(Locale.ENGLISH)?.let { return "ISIN:$it" }

        val company = normalizeCompany(firstString(obj, "companyName", "company", "issuerName", "securityName", "name"))
        val start = parseDate(first(obj, "issueStartDate", "startDate", "openDate", "issueOpenDate"))
        val end = parseDate(first(obj, "issueEndDate", "endDate", "closeDate", "issueCloseDate"))
        if (company.isNotBlank() && (start != null || end != null)) {
            return "COMPANY_DATES:" + sha256(company + "|" + (start?.toString() ?: "") + "|" + (end?.toString() ?: "")).take(20)
        }

        firstString(obj, "symbol", "trading_symbol", "issue_symbol", "tradingSymbol", "securitySymbol")
            ?.uppercase(Locale.ENGLISH)
            ?.let { return "SYMBOL:$it" }
        return null
    }

    private fun first(obj: JSONObject, vararg names: String): Any? {
        val wanted = names.map { normKey(it) }.toSet()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (normKey(key) in wanted) {
                val value = obj.opt(key)
                if (value != null && value !== JSONObject.NULL && value.toString().isNotBlank()) return value
            }
        }
        return null
    }

    private fun firstString(obj: JSONObject, vararg names: String): String? =
        first(obj, *names)?.toString()?.trim()?.takeIf { it.isNotBlank() }

    private fun walkJson(node: Any?, visit: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                visit(node)
                val keys = node.keys()
                while (keys.hasNext()) walkJson(node.opt(keys.next()), visit)
            }
            is JSONArray -> for (i in 0 until node.length()) walkJson(node.opt(i), visit)
        }
    }

    private fun mergeJson(base: JSONObject, overlay: JSONObject): JSONObject {
        val result = JSONObject(base.toString())
        val keys = overlay.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result.put(key, overlay.opt(key))
        }
        return result
    }

    private fun parseDate(value: Any?): LocalDate? {
        val text = value?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        DATE_FORMATS.forEach { formatter ->
            runCatching { LocalDate.parse(text, formatter) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun normalizeCompany(value: String?): String {
        var text = value.orEmpty().lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]+"), "")
        for (suffix in listOf("privatelimited", "pvtltd", "limited", "ltd")) {
            if (text.endsWith(suffix)) {
                text = text.dropLast(suffix.length)
                break
            }
        }
        return text
    }

    private fun normKey(value: String): String =
        value.lowercase(Locale.ENGLISH).filter { it.isLetterOrDigit() }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun allowed(value: String?): Boolean =
        value?.trim()?.lowercase(Locale.ENGLISH) in setOf("1", "true", "yes")

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i += 1
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
            i += 1
        }
        values += current.toString()
        return values
    }

    private fun savePlan(plan: ResearchPlan) {
        prefs.edit()
            .putString(KEY_PLAN_JSON, planToJson(plan).toString())
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun loadCachedPlan(): ResearchPlan? {
        val text = prefs.getString(KEY_PLAN_JSON, null) ?: return null
        return runCatching { planFromJson(JSONObject(text)) }.getOrNull()
    }

    private fun planToJson(plan: ResearchPlan): JSONObject = JSONObject().apply {
        put("generated_at", plan.generatedAt)
        put("source_ready", plan.sourceReady)
        put("research_health", plan.researchHealth)
        put("calendar_ready", plan.calendarReady)
        put("next_trading_day", plan.nextTradingDay)
        put("candidate_count", plan.candidateCount)
        put("nse_identity_confirmed_count", plan.nseIdentityConfirmedCount)
        put("groww_resolved_count", plan.growwResolvedCount)
        put("groww_pending_count", plan.growwPendingCount)
        put("next_trading_day_candidates", JSONArray(plan.nextTradingDayCandidates.map { candidateToJson(it) }))
        put("week_candidates", JSONArray(plan.weekCandidates.map { candidateToJson(it) }))
        put("all_known_candidates", JSONArray(plan.allKnownCandidates.map { candidateToJson(it) }))
        put("errors", JSONArray(plan.errors))
    }

    private fun planFromJson(json: JSONObject): ResearchPlan = ResearchPlan(
        generatedAt = json.optString("generated_at").ifBlank { null },
        sourceReady = json.optBoolean("source_ready", false),
        researchHealth = json.optString("research_health", "UNKNOWN"),
        calendarReady = json.optBoolean("calendar_ready", false),
        nextTradingDay = json.optString("next_trading_day").ifBlank { null },
        candidateCount = json.optInt("candidate_count", 0),
        nseIdentityConfirmedCount = json.optInt("nse_identity_confirmed_count", 0),
        growwResolvedCount = json.optInt("groww_resolved_count", 0),
        growwPendingCount = json.optInt("groww_pending_count", 0),
        nextTradingDayCandidates = candidateArray(json.optJSONArray("next_trading_day_candidates")),
        weekCandidates = candidateArray(json.optJSONArray("week_candidates")),
        allKnownCandidates = candidateArray(json.optJSONArray("all_known_candidates")),
        errors = stringArray(json.optJSONArray("errors"))
    )

    private fun candidateToJson(c: ResearchCandidate): JSONObject = JSONObject().apply {
        put("candidate_id", c.candidateId)
        put("lifecycle_state", c.lifecycleState)
        put("symbol", c.symbol)
        put("company_name", c.companyName)
        put("listing_date", c.listingDate)
        put("issue_start_date", c.issueStartDate)
        put("issue_end_date", c.issueEndDate)
        put("official_issue_id", c.officialIssueId)
        put("isin", c.isin)
        put("board", c.board)
        put("is_sme", c.isSme)
        put("issue_status", c.issueStatus)
        put("nse_listing_confirmed", c.nseListingConfirmed)
        put("groww_symbol", c.growwSymbol)
        put("groww_series", c.growwSeries)
        put("buy_allowed", c.buyAllowed)
        put("sell_allowed", c.sellAllowed)
        put("symbol_resolved", c.symbolResolved)
        put("groww_resolution_status", c.growwResolutionStatus)
        put("resolution_status", c.resolutionStatus)
        put("issue_price_text", c.issuePriceText)
        put("subscription_multiple", c.subscriptionMultiple)
        put("trading_day_number", c.tradingDayNumber)
        put("groww_lot_size", c.growwLotSize)
    }

    private fun candidateArray(arr: JSONArray?): List<ResearchCandidate> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
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
                )
            }
        }
    }

    private fun stringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val value = arr.optString(i)
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.take(200)?.ifBlank { null } ?: error.javaClass.simpleName

    private data class RawIssue(val obj: JSONObject, val source: String)

    private data class NormalizedCandidate(
        val candidateId: String,
        val source: String,
        val symbol: String?,
        val companyName: String,
        val listingDate: LocalDate?,
        val issueStartDate: LocalDate?,
        val issueEndDate: LocalDate?,
        val officialIssueId: String?,
        val isin: String?,
        val board: String?,
        val isSme: Boolean,
        val issueStatus: String?,
        val issuePriceText: String?,
        val subscriptionMultiple: Double?
    )

    private data class InstrumentResolution(
        val status: String,
        val row: Map<String, String>?
    )

    private class NseSession {
        private val previous = CookieHandler.getDefault()
        private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        private var primed = false

        init {
            CookieHandler.setDefault(cookies)
        }

        fun close() {
            CookieHandler.setDefault(previous)
        }

        fun json(path: String): Any {
            prime()
            var response = get(path)
            if (response.first in setOf(401, 403)) {
                primed = false
                prime()
                response = get(path)
            }
            if (response.first !in 200..299) {
                throw IllegalStateException("NSE returned HTTP " + response.first)
            }
            val text = response.second.trim()
            return when {
                text.startsWith("{") -> JSONObject(text)
                text.startsWith("[") -> JSONArray(text)
                else -> throw IllegalStateException("NSE returned non-JSON response")
            }
        }

        private fun prime() {
            if (primed) return
            val connection = (URL(NSE_BASE + "/market-data/all-upcoming-issues-ipo").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            val code = connection.responseCode
            runCatching {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                stream?.close()
            }
            connection.disconnect()
            if (code !in 200..399) throw IllegalStateException("NSE session prime returned HTTP $code")
            primed = true
        }

        private fun get(path: String): Pair<Int, String> {
            val connection = (URL(NSE_BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Referer", NSE_BASE + "/market-data/all-upcoming-issues-ipo")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            return code to body
        }
    }

    companion object {
        private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        private const val PREFS_NAME = "ipo_sentinel_direct_research"
        private const val KEY_PLAN_JSON = "research_plan_json"
        private const val KEY_SAVED_AT = "research_plan_saved_at"
        private const val CACHE_TTL_MS = 15L * 60L * 1000L

        private const val NSE_BASE = "https://www.nseindia.com"
        private const val NSE_UPCOMING = "/api/all-upcoming-issues?category=ipo"
        private const val NSE_CURRENT = "/api/ipo-current-issue"
        private const val NSE_HOLIDAYS = "/api/holiday-master?type=trading"
        private const val NSE_FORTHCOMING = "/api/new-listing-today?index=ForthListing"
        private const val NSE_RECENT = "/api/new-listing-today?index=RecentListing"
        private const val GROWW_INSTRUMENT_CSV = "https://growwapi-assets.groww.in/instruments/instrument.csv"

        private val FINAL_IDENTITY_SOURCES = setOf("NSE_FORTHCOMING_LISTING", "NSE_RECENT_LISTING")
        private val DATE_FORMATS = listOf(
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.ENGLISH)
        )
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Mobile Safari/537.36"
    }
}
