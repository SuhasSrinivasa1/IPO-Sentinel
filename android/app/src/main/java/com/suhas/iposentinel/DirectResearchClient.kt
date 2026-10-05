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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ResearchSourceStatus(
    val name: String,
    val status: String,
    val usingCachedData: Boolean,
    val lastSuccessAt: String?,
    val error: String?
)

/**
 * Direct, read-only IPO research path.
 *
 * Official NSE discovery is kept separate from final listing identity and from
 * Groww instrument resolution. Company-name association is research-only; it
 * is never used to authorize an executable identity.
 */
class DirectResearchClient(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun refreshPlan(force: Boolean = false): Pair<ApiResult, ResearchPlan?> =
        withContext(Dispatchers.IO) {
            val cached = loadCachedPlan()
            val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
            if (!force && cached != null && System.currentTimeMillis() - savedAt < PLAN_CACHE_TTL_MS) {
                return@withContext ApiResult(true, 200, "{}", null) to cached
            }

            try {
                val plan = buildPlan(cached)
                savePlan(plan)
                ApiResult(true, 200, "{}", null) to plan
            } catch (error: Exception) {
                val message = "DIRECT_REFRESH:" + safeMessage(error)
                if (cached != null) {
                    val degraded = cached.copy(
                        researchHealth = "DEGRADED",
                        errors = (cached.errors + message).distinct()
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
            .filter { (it.tradingDayNumber ?: 0) in 1..30 }
            .sortedWith(compareBy<ResearchCandidate> { it.tradingDayNumber ?: 99 }.thenBy { it.symbol ?: "" })

        val ranked = (plan.nextTradingDayCandidates + active30)
            .distinctBy { it.candidateId }
            .map { rankCandidate(it, plan.nextTradingDay) }
            .sortedByDescending { it.researchScore }
            .take(3)

        return ApiResult(true, 200, "{}", null) to ResearchDashboard(
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
            coverageRule = "Research only: official NSE identity -> exact Groww NSE/CASH instrument. Live prices, depth and order execution are not enabled."
        )
    }

    fun cachedPlan(): ResearchPlan? = loadCachedPlan()

    fun sourceStatuses(): List<ResearchSourceStatus> {
        val array = runCatching {
            JSONArray(prefs.getString(KEY_SOURCE_STATUSES, "[]"))
        }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(
                    ResearchSourceStatus(
                        name = obj.optString("name"),
                        status = obj.optString("status", "UNKNOWN"),
                        usingCachedData = obj.optBoolean("using_cached_data", false),
                        lastSuccessAt = obj.optString("last_success_at").ifBlank { null },
                        error = obj.optString("error").ifBlank { null }
                    )
                )
            }
        }
    }

    private fun buildPlan(priorPlan: ResearchPlan?): ResearchPlan {
        val now = ZonedDateTime.now(IST)
        val today = now.toLocalDate()
        val errors = mutableListOf<String>()
        val statuses = mutableListOf<ResearchSourceStatus>()
        val rawIssues = mutableListOf<RawIssue>()
        val session = NseSession()

        var calendarReady = false
        var holidays = emptySet<LocalDate>()
        var discoveryAvailable = false
        var identityAvailable = false

        try {
            val holidayLoad = loadNseSource(session, "NSE_HOLIDAY_SOURCE", NSE_HOLIDAYS)
            statuses += holidayLoad.status
            holidayLoad.value?.let {
                holidays = parseHolidays(it)
                calendarReady = holidays.isNotEmpty()
                if (!calendarReady) errors += "NSE_HOLIDAY_SOURCE:EMPTY_OR_INVALID"
            }
            holidayLoad.status.error?.let { errors += "NSE_HOLIDAY_SOURCE:" + it }

            for ((path, source) in listOf(
                NSE_UPCOMING to "NSE_UPCOMING_ISSUES",
                NSE_CURRENT to "NSE_CURRENT_ISSUES"
            )) {
                val load = loadNseSource(session, source, path)
                statuses += load.status
                if (load.value != null) {
                    discoveryAvailable = true
                    extractIssueRecords(load.value).forEach { mergeResearch(rawIssues, it, source) }
                }
                load.status.error?.let { errors += source + ":" + it }
            }

            val forth = loadNseSource(session, "NSE_FORTHCOMING_LISTING", NSE_FORTHCOMING)
            statuses += forth.status
            if (forth.value != null) {
                identityAvailable = true
                extractForthcomingRecords(forth.value)
                    .forEach { mergeFinalIdentity(rawIssues, it, "NSE_FORTHCOMING_LISTING") }
            }
            forth.status.error?.let { errors += "NSE_FORTHCOMING_LISTING:" + it }

            val recent = loadNseSource(session, "NSE_RECENT_LISTING", NSE_RECENT)
            statuses += recent.status
            if (recent.value != null) {
                identityAvailable = true
                val cutoff = today.minusDays(50)
                extractForthcomingRecords(recent.value)
                    .filter { row ->
                        parseDate(first(row, "listingDate", "dateOfListing", "date_of_listing", "date", "listing_date"))
                            ?.let { !it.isBefore(cutoff) } == true
                    }
                    .forEach { appendIfNew(rawIssues, it, "NSE_RECENT_LISTING") }
            }
            recent.status.error?.let { errors += "NSE_RECENT_LISTING:" + it }
        } finally {
            session.close()
        }

        saveSourceStatuses(statuses)

        val normalized = rawIssues.map { normalizeCandidate(it.obj, it.source) }
        val officialSymbols = normalized
            .filter { it.source in FINAL_IDENTITY_SOURCES && it.symbol != null && it.listingDate != null }
            .mapNotNull { it.symbol }
            .toSet()
        val officialIsins = normalized
            .filter { it.source in FINAL_IDENTITY_SOURCES }
            .mapNotNull { it.isin }
            .toSet()

        val (instrumentRows, instrumentWarning) =
            loadGrowwInstrumentRowsWithCache(officialSymbols, officialIsins)
        instrumentWarning?.let { errors += it }

        val nextTradingDay = if (calendarReady) nextTradingDay(today, holidays) else nextWeekday(today)
        val weekEnd = nextTradingDay.plusDays(6)

        val candidates = normalized.map { item ->
            val identityVerified = item.source in FINAL_IDENTITY_SOURCES &&
                item.symbol != null &&
                item.listingDate != null

            val resolution = if (identityVerified) {
                resolveInstrument(instrumentRows, item.symbol, item.isin)
            } else {
                InstrumentResolution("WAIT_NSE_IDENTITY", null)
            }

            val dayNumber = if (
                calendarReady && item.listingDate != null && !item.listingDate.isAfter(today)
            ) tradingDayNumber(item.listingDate, today, holidays) else null

            val lifecycle = when {
                !identityVerified -> "DISCOVERED"
                resolution.status != "RESOLVED" -> "IDENTITY_VERIFIED"
                !calendarReady -> "GROWW_SYMBOL_VERIFIED"
                else -> "READY_FOR_RESEARCH"
            }

            val resolutionStatus = when {
                !identityVerified && item.symbol == null -> "WAIT_OFFICIAL_NSE_SYMBOL"
                !identityVerified -> "WAIT_OFFICIAL_LISTING_IDENTITY"
                resolution.status != "RESOLVED" -> resolution.status
                item.listingDate == today -> "WAIT_LIVE_CONFIRMATION_LISTING_DAY"
                dayNumber != null && dayNumber in 1..30 -> "RESEARCH_D" + dayNumber + "_WAIT_LIVE_CONFIRMATION"
                item.listingDate?.isAfter(today) == true -> "PRE_LISTING_RESEARCH"
                else -> "RESEARCH_READY"
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
                nseListingConfirmed = identityVerified,
                growwSymbol = row?.get("groww_symbol")?.takeIf { it.isNotBlank() },
                growwSeries = row?.get("series")?.takeIf { it.isNotBlank() },
                buyAllowed = allowed(row?.get("buy_allowed")),
                sellAllowed = allowed(row?.get("sell_allowed")),
                symbolResolved = identityVerified && resolution.status == "RESOLVED",
                growwResolutionStatus = resolution.status,
                resolutionStatus = resolutionStatus,
                issuePriceText = item.issuePriceText,
                subscriptionMultiple = item.subscriptionMultiple,
                tradingDayNumber = dayNumber,
                growwLotSize = row?.get("lot_size")?.toDoubleOrNull()?.toInt()
            )
        }.toMutableList()

        // Preserve last known-good candidates when a transient source failure drops
        // a record from the current refresh. Preserved rows never become execution-ready.
        priorPlan?.allKnownCandidates.orEmpty().forEach { saved ->
            if (candidates.any { it.candidateId == saved.candidateId }) return@forEach
            val listing = saved.listingDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val issueEnd = saved.issueEndDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val anchor = listing ?: issueEnd ?: return@forEach
            if (anchor.isBefore(today.minusDays(60)) || anchor.isAfter(today.plusDays(60))) return@forEach

            val dayNo = if (calendarReady && listing != null && !listing.isAfter(today)) {
                tradingDayNumber(listing, today, holidays)
            } else saved.tradingDayNumber

            candidates += saved.copy(
                tradingDayNumber = dayNo,
                lifecycleState = if (saved.symbolResolved && calendarReady) "READY_FOR_RESEARCH" else saved.lifecycleState,
                resolutionStatus = if (dayNo != null && dayNo in 1..30) {
                    "CACHED_RESEARCH_D" + dayNo + "_WAIT_LIVE_CONFIRMATION"
                } else {
                    "CACHED_LAST_KNOWN_GOOD"
                }
            )
        }

        val unique = candidates.distinctBy { it.candidateId }
        val nextCandidates = unique.filter { it.listingDate == nextTradingDay.toString() }
        val weekCandidates = unique.filter {
            val day = it.listingDate?.let { text -> runCatching { LocalDate.parse(text) }.getOrNull() }
            day != null && !day.isBefore(nextTradingDay) && !day.isAfter(weekEnd)
        }.sortedWith(
            compareBy<ResearchCandidate> { it.listingDate ?: "" }
                .thenBy { it.symbol ?: "" }
                .thenBy { it.companyName }
        )

        val sourceReady = discoveryAvailable && identityAvailable
        val health = when {
            statuses.all { it.status == "FAILED" } -> "FAILED"
            !sourceReady || statuses.any { it.status != "FRESH" } || instrumentWarning != null -> "DEGRADED"
            else -> "OK"
        }

        return ResearchPlan(
            generatedAt = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            sourceReady = sourceReady,
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
        var score = 35.0
        val reasons = mutableListOf<String>()
        if (candidate.nseListingConfirmed) {
            score += 18.0
            reasons += "OFFICIAL_NSE_IDENTITY"
        }
        if (candidate.symbolResolved) {
            score += 18.0
            reasons += "EXACT_GROWW_NSE_CASH_MATCH"
        }
        candidate.subscriptionMultiple?.let { sub ->
            when {
                sub >= 10.0 -> { score += 12.0; reasons += "HIGH_SUBSCRIPTION" }
                sub >= 3.0 -> { score += 8.0; reasons += "STRONG_SUBSCRIPTION" }
                sub >= 1.0 -> { score += 4.0; reasons += "FULLY_SUBSCRIBED" }
                else -> { score -= 5.0; reasons += "WEAK_SUBSCRIPTION" }
            }
        }
        if (candidate.isSme) {
            score -= 3.0
            reasons += "SME_LIQUIDITY_RISK"
        } else {
            score += 3.0
            reasons += "MAINBOARD"
        }
        if (candidate.listingDate == nextTradingDay) {
            score += 8.0
            reasons += "NEXT_LISTING_DAY"
        }
        if ((candidate.tradingDayNumber ?: 0) in 1..30) {
            score += 4.0
            reasons += "ACTIVE_D1_D30_WINDOW"
        }

        return ResearchPick(
            symbol = candidate.symbol,
            companyName = candidate.companyName,
            direction = null,
            researchScore = score.coerceIn(0.0, 100.0),
            preMarketBias = "WAIT_LIVE_CONFIRMATION",
            entryPrice = null,
            stopLoss = null,
            target1 = null,
            target2 = null,
            quantity = null,
            confidence = null,
            tradingDayNumber = candidate.tradingDayNumber,
            board = if (candidate.isSme) "SME" else "MAINBOARD",
            reasons = reasons,
            note = "Research ranking only. No live signal, entry, stop, target or confidence is asserted."
        )
    }

    private fun loadNseSource(session: NseSession, name: String, path: String): SourceLoad {
        return try {
            val value = session.json(path)
            saveSourcePayload(name, value)
            SourceLoad(
                value,
                ResearchSourceStatus(
                    name = name,
                    status = "FRESH",
                    usingCachedData = false,
                    lastSuccessAt = Instant.now().toString(),
                    error = null
                )
            )
        } catch (error: Exception) {
            val cached = loadSourcePayload(name)
            if (cached != null) {
                SourceLoad(
                    cached.first,
                    ResearchSourceStatus(
                        name = name,
                        status = "CACHED",
                        usingCachedData = true,
                        lastSuccessAt = cached.second,
                        error = safeMessage(error)
                    )
                )
            } else {
                SourceLoad(
                    null,
                    ResearchSourceStatus(
                        name = name,
                        status = "FAILED",
                        usingCachedData = false,
                        lastSuccessAt = null,
                        error = safeMessage(error)
                    )
                )
            }
        }
    }

    private fun saveSourcePayload(name: String, value: Any) {
        prefs.edit()
            .putString(KEY_SOURCE_PAYLOAD_PREFIX + name, jsonText(value))
            .putLong(KEY_SOURCE_SAVED_PREFIX + name, System.currentTimeMillis())
            .apply()
    }

    private fun loadSourcePayload(name: String): Pair<Any, String>? {
        val savedAt = prefs.getLong(KEY_SOURCE_SAVED_PREFIX + name, 0L)
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > SOURCE_CACHE_MAX_AGE_MS) return null
        val text = prefs.getString(KEY_SOURCE_PAYLOAD_PREFIX + name, null) ?: return null
        val value = parseJsonText(text) ?: return null
        return value to Instant.ofEpochMilli(savedAt).toString()
    }

    private fun saveSourceStatuses(statuses: List<ResearchSourceStatus>) {
        val array = JSONArray()
        statuses.forEach { source ->
            array.put(
                JSONObject()
                    .put("name", source.name)
                    .put("status", source.status)
                    .put("using_cached_data", source.usingCachedData)
                    .put("last_success_at", source.lastSuccessAt)
                    .put("error", source.error)
            )
        }
        prefs.edit().putString(KEY_SOURCE_STATUSES, array.toString()).apply()
    }

    private fun normalizeCandidate(obj: JSONObject, source: String): NormalizedCandidate {
        val symbol = firstString(obj, "symbol", "trading_symbol", "issue_symbol", "tradingSymbol", "securitySymbol")
            ?.uppercase(Locale.ENGLISH)
        val company = firstString(obj, "companyName", "company", "issuerName", "securityName", "name")
            ?: symbol ?: "Unknown issuer"
        val listing = parseDate(first(obj, "listingDate", "dateOfListing", "date_of_listing", "listing_date", "tentativeListingDate", "date"))
        val start = parseDate(first(obj, "issueStartDate", "startDate", "openDate", "issueOpenDate"))
        val end = parseDate(first(obj, "issueEndDate", "endDate", "closeDate", "issueCloseDate"))
        val isin = firstString(obj, "isin", "isinCode")?.uppercase(Locale.ENGLISH)
        val board = firstString(obj, "series", "board", "category", "issueType")
        val subscription = firstString(obj, "noOfTime", "subscriptionMultiple", "subscription")
            ?.replace(",", "")?.toDoubleOrNull()

        return NormalizedCandidate(
            candidateId = candidateIdentity(obj)
                ?: "RESEARCH:" + sha256(
                    normalizeCompany(company) + "|" + (start?.toString() ?: "") + "|" +
                        (end?.toString() ?: "") + "|" + (symbol ?: "")
                ).take(20),
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
                "listingDate", "tentativeListingDate", "dateOfListing", "issuePrice", "status"
            ).any { first(obj, it) != null }
            if (hasCompany && hasFact && candidateIdentity(obj) != null) {
                out += JSONObject(obj.toString())
            }
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
        return out.distinctBy {
            firstString(it, "symbol", "tradingSymbol", "securitySymbol")
                ?.uppercase(Locale.ENGLISH) ?: it.toString()
        }
    }

    private fun mergeResearch(list: MutableList<RawIssue>, item: JSONObject, source: String) {
        val key = candidateIdentity(item) ?: return
        val index = list.indexOfFirst { candidateIdentity(it.obj) == key }
        if (index >= 0) list[index] = RawIssue(mergeJson(list[index].obj, item), source)
        else list += RawIssue(JSONObject(item.toString()), source)
    }

    private fun mergeFinalIdentity(list: MutableList<RawIssue>, item: JSONObject, source: String) {
        val finalSymbol = firstString(item, "symbol", "tradingSymbol", "securitySymbol")
            ?.uppercase(Locale.ENGLISH)
        val finalIsin = firstString(item, "isin", "isinCode")?.uppercase(Locale.ENGLISH)

        val matches = list.indices.filter { index ->
            val prior = list[index].obj
            val priorSymbol = firstString(prior, "symbol", "trading_symbol", "issue_symbol", "tradingSymbol", "securitySymbol")
                ?.uppercase(Locale.ENGLISH)
            val priorIsin = firstString(prior, "isin", "isinCode")?.uppercase(Locale.ENGLISH)
            (finalIsin != null && priorIsin != null && finalIsin == priorIsin) ||
                (finalSymbol != null && priorSymbol != null && finalSymbol == priorSymbol)
        }

        if (matches.size == 1) {
            val index = matches.first()
            list[index] = RawIssue(mergeJson(list[index].obj, item), source)
        } else {
            appendIfNew(list, item, source)
        }
    }

    private fun appendIfNew(list: MutableList<RawIssue>, item: JSONObject, source: String) {
        val symbol = firstString(item, "symbol", "tradingSymbol", "securitySymbol")
            ?.uppercase(Locale.ENGLISH).orEmpty()
        val isin = firstString(item, "isin", "isinCode")?.uppercase(Locale.ENGLISH).orEmpty()

        val duplicate = list.any { prior ->
            val pSymbol = firstString(prior.obj, "symbol", "tradingSymbol", "securitySymbol")
                ?.uppercase(Locale.ENGLISH).orEmpty()
            val pIsin = firstString(prior.obj, "isin", "isinCode")?.uppercase(Locale.ENGLISH).orEmpty()
            (symbol.isNotBlank() && symbol == pSymbol) || (isin.isNotBlank() && isin == pIsin)
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

    private fun loadGrowwInstrumentRowsWithCache(
        symbols: Set<String>,
        isins: Set<String>
    ): Pair<List<Map<String, String>>, String?> {
        if (symbols.isEmpty()) return emptyList<Map<String, String>>() to null
        return try {
            val rows = loadGrowwInstrumentRows(symbols, isins)
            saveInstrumentRows(rows)
            rows to null
        } catch (error: Exception) {
            val cached = loadInstrumentRows(symbols, isins)
            if (cached.isNotEmpty()) {
                cached to ("GROWW_INSTRUMENT_MASTER:CACHED:" + safeMessage(error))
            } else {
                emptyList<Map<String, String>>() to ("GROWW_INSTRUMENT_MASTER:" + safeMessage(error))
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
            setRequestProperty("User-Agent", BROWSER_UA)
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("Groww instrument master returned HTTP " + code)
        }

        val rows = mutableListOf<Map<String, String>>()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val headerLine = reader.readLine() ?: return@use
            val headers = parseCsvLine(headerLine).map { it.trim() }
            reader.forEachLine { line ->
                val values = parseCsvLine(line)
                if (values.size < headers.size) return@forEachLine
                val row = headers.indices.associate { idx -> headers[idx] to values.getOrElse(idx) { "" } }
                if (!row["exchange"].equals("NSE", true) || !row["segment"].equals("CASH", true)) return@forEachLine
                val symbol = row["trading_symbol"]?.trim()?.uppercase(Locale.ENGLISH).orEmpty()
                val isin = row["isin"]?.trim()?.uppercase(Locale.ENGLISH).orEmpty()
                if (symbol in symbols || (isin.isNotBlank() && isin in isins)) rows += row
            }
        }
        connection.disconnect()
        return rows
    }

    private fun saveInstrumentRows(rows: List<Map<String, String>>) {
        val array = JSONArray()
        rows.forEach { row ->
            val obj = JSONObject()
            row.forEach { (key, value) -> obj.put(key, value) }
            array.put(obj)
        }
        prefs.edit()
            .putString(KEY_INSTRUMENT_ROWS, array.toString())
            .putLong(KEY_INSTRUMENT_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun loadInstrumentRows(symbols: Set<String>, isins: Set<String>): List<Map<String, String>> {
        val savedAt = prefs.getLong(KEY_INSTRUMENT_SAVED_AT, 0L)
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > INSTRUMENT_CACHE_MAX_AGE_MS) return emptyList()
        val array = runCatching { JSONArray(prefs.getString(KEY_INSTRUMENT_ROWS, "[]")) }.getOrElse { return emptyList() }
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val map = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.optString(key)
                }
                val symbol = map["trading_symbol"]?.uppercase(Locale.ENGLISH).orEmpty()
                val isin = map["isin"]?.uppercase(Locale.ENGLISH).orEmpty()
                if (symbol in symbols || (isin.isNotBlank() && isin in isins)) add(map)
            }
        }
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
            ?.let { return "ISSUE:" + it }
        firstString(obj, "isin", "isinCode")?.uppercase(Locale.ENGLISH)?.let { return "ISIN:" + it }

        val company = normalizeCompany(firstString(obj, "companyName", "company", "issuerName", "securityName", "name"))
        val start = parseDate(first(obj, "issueStartDate", "startDate", "openDate", "issueOpenDate"))
        val end = parseDate(first(obj, "issueEndDate", "endDate", "closeDate", "issueCloseDate"))
        if (company.isNotBlank() && (start != null || end != null)) {
            return "COMPANY_DATES:" + sha256(company + "|" + (start?.toString() ?: "") + "|" + (end?.toString() ?: "")).take(20)
        }

        firstString(obj, "symbol", "trading_symbol", "issue_symbol", "tradingSymbol", "securitySymbol")
            ?.uppercase(Locale.ENGLISH)?.let { return "SYMBOL:" + it }
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
                        lifecycleState = obj.optString("lifecycle_state", "DISCOVERED"),
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

    private fun jsonText(value: Any): String = when (value) {
        is JSONObject -> value.toString()
        is JSONArray -> value.toString()
        else -> value.toString()
    }

    private fun parseJsonText(text: String): Any? {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrNull()
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            else -> null
        }
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.take(200)?.ifBlank { null } ?: error.javaClass.simpleName

    private data class SourceLoad(val value: Any?, val status: ResearchSourceStatus)
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

    private data class HttpResponse(
        val code: Int,
        val body: String,
        val contentType: String
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
            var lastError = "NSE request failed"
            repeat(3) { attempt ->
                prime()
                val response = get(path)
                if (response.code in 200..299) {
                    val text = response.body.trim()
                    val blocked = text.startsWith("<", true) ||
                        text.contains("captcha", true) ||
                        text.contains("access denied", true) ||
                        text.contains("request rejected", true)
                    val looksJson = text.startsWith("{") || text.startsWith("[")
                    if (blocked || !looksJson) {
                        throw IllegalStateException("NSE returned HTML/block content")
                    }
                    if (
                        response.contentType.isNotBlank() &&
                        !response.contentType.contains("json", true) &&
                        !looksJson
                    ) {
                        throw IllegalStateException("NSE returned unexpected Content-Type")
                    }
                    return if (text.startsWith("{")) JSONObject(text) else JSONArray(text)
                }

                lastError = "NSE returned HTTP " + response.code
                if (response.code !in setOf(401, 403, 429) || attempt == 2) {
                    throw IllegalStateException(lastError)
                }

                primed = false
                Thread.sleep(500L * (attempt + 1))
            }
            throw IllegalStateException(lastError)
        }

        private fun prime() {
            if (primed) return
            val connection = (URL(NSE_BASE + "/market-data/all-upcoming-issues-ipo").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Connection", "keep-alive")
            }
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code !in 200..399) throw IllegalStateException("NSE session bootstrap returned HTTP " + code)
            if (body.contains("access denied", true) || body.contains("captcha", true)) {
                throw IllegalStateException("NSE session bootstrap was blocked")
            }
            primed = true
        }

        private fun get(path: String): HttpResponse {
            val connection = (URL(NSE_BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Referer", NSE_BASE + "/market-data/all-upcoming-issues-ipo")
                setRequestProperty("Connection", "keep-alive")
            }
            val code = connection.responseCode
            val type = connection.contentType.orEmpty()
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            return HttpResponse(code, body, type)
        }
    }

    companion object {
        private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        private const val PREFS_NAME = "ipo_sentinel_direct_research"
        private const val KEY_PLAN_JSON = "research_plan_json"
        private const val KEY_SAVED_AT = "research_plan_saved_at"
        private const val KEY_SOURCE_STATUSES = "research_source_statuses"
        private const val KEY_SOURCE_PAYLOAD_PREFIX = "source_payload_"
        private const val KEY_SOURCE_SAVED_PREFIX = "source_saved_at_"
        private const val KEY_INSTRUMENT_ROWS = "groww_instrument_rows"
        private const val KEY_INSTRUMENT_SAVED_AT = "groww_instrument_saved_at"
        private const val PLAN_CACHE_TTL_MS = 30L * 60L * 1000L
        private const val SOURCE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        private const val INSTRUMENT_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L

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
