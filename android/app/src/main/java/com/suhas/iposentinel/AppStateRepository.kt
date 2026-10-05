package com.suhas.iposentinel

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppState(
    val initialized: Boolean = false,
    val isRefreshing: Boolean = false,
    val isReplaying: Boolean = false,
    val connectionStatus: ConnectionStatus? = null,
    val validation: ValidationStatus? = null,
    val researchPlan: ResearchPlan? = null,
    val researchSources: List<ResearchSourceStatus> = emptyList(),
    val usingCachedResearch: Boolean = false,
    val calls: List<RecommendationCall> = emptyList(),
    val brokerTruth: BrokerTruthSnapshot? = null,
    val signalScan: SignalScanSummary? = null,
    val replaySummary: ReplayRunSummary? = null,
    val strategySummary: StrategySummary = LocalStrategyCatalog.summary(),
    val tradeReviewSettings: TradeReviewSettings = TradeReviewSettings(),
    val liveTradingReadiness: GrowwTradingReadiness? = null,
    val lastValidatedAtMillis: Long? = null,
    val lastError: String? = null
) {
    val growwConnectionReady: Boolean
        get() = validation?.let {
            it.growwAuthOk && it.staticIpMatches && it.staticIpConfirmed && it.secretStoreReady
        } == true

    val liveCalls: List<RecommendationCall>
        get() = calls.filter { it.state == "LIVE" }.sortedByDescending { it.recommendedAt }

    val closedCalls: List<RecommendationCall>
        get() = calls.filter { it.state == "CLOSED" }.sortedByDescending { it.closedAt ?: it.lastUpdatedAt }

    private val todayIst: LocalDate
        get() = LocalDate.now(ZoneId.of("Asia/Kolkata"))

    val todayClosedCalls: List<RecommendationCall>
        get() = closedCalls.filter { call ->
            call.closedAt?.let { at ->
                runCatching { Instant.parse(at).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate() == todayIst }
                    .getOrDefault(false)
            } == true
        }

    val todayClosedShadowPnlRupees: Double
        get() = todayClosedCalls.sumOf { it.shadowPnlRupees }

    val todayOpenShadowPnlRupees: Double
        get() = liveCalls
            .filter { it.shadowSessionPnlDate == todayIst.toString() }
            .sumOf { it.shadowSessionPnlRupees }

    val todayShadowPnlRupees: Double
        get() = todayOpenShadowPnlRupees + todayClosedShadowPnlRupees

    val brokerRealisedPnlRupees: Double?
        get() = brokerTruth?.takeIf { it.error == null }?.positions?.sumOf { it.realisedPnl ?: 0.0 }

    val executionReady: Boolean
        get() = false
}

class AppStateRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val groww = DirectGrowwClient(appContext)
    private val research = DirectResearchClient(appContext)
    private val callLedger = CallLedgerStore(appContext)
    private val brokerStore = BrokerTruthStore(appContext)
    private val strategyStore = StrategyEvidenceStore(appContext)
    private val signalScanner = LiveSignalScanner(appContext)
    private val replayEngine = ShadowReplayEngine(appContext)
    private val tradeReviewStore = TradeReviewSettingsStore(appContext)
    private val tradeReviewGateway = TradeReviewGateway(appContext)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        AppState(
            strategySummary = strategyStore.summary(),
            replaySummary = strategyStore.lastReplay(),
            calls = callLedger.load(),
            brokerTruth = brokerStore.load(),
            tradeReviewSettings = tradeReviewStore.load(),
            liveTradingReadiness = null
        )
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun snapshot(): AppState = _state.value

    suspend fun initialize() = mutex.withLock {
        if (_state.value.initialized || _state.value.isRefreshing) return@withLock
        RecoveryScheduler.ensureScheduled(appContext)
        ResearchLearningScheduler.ensureScheduled(appContext)
        callLedger.migrateLegacyResearchRows()

        val (_, status) = groww.fetchStatus()
        val cachedPlan = research.cachedPlan()

        _state.value = _state.value.copy(
            isRefreshing = true,
            connectionStatus = status,
            validation = groww.lastValidation()?.copy(liveExecutionReady = false),
            researchPlan = cachedPlan,
            researchSources = research.sourceStatuses(),
            usingCachedResearch = research.sourceStatuses().any { it.usingCachedData },
            calls = callLedger.load(),
            brokerTruth = brokerStore.load(),
            tradeReviewSettings = tradeReviewStore.load(),
            replaySummary = strategyStore.lastReplay(),
            strategySummary = strategyStore.summary(),
            lastValidatedAtMillis = groww.lastValidationAtMillis(),
            lastError = null
        )

        val (_, plan) = research.refreshPlan(force = false)
        if (plan != null) {
            _state.value = _state.value.copy(
                researchPlan = plan,
                researchSources = research.sourceStatuses(),
                usingCachedResearch = research.sourceStatuses().any { it.usingCachedData }
            )
        }

        val configured = status?.growwConfigured == true
        if (configured) {
            val (validationResult, value) = groww.validate()
            if (validationResult.ok && value != null) {
                _state.value = _state.value.copy(
                    validation = value.copy(liveExecutionReady = false),
                    lastValidatedAtMillis = groww.lastValidationAtMillis()
                )
            } else {
                _state.value = _state.value.copy(lastError = validationResult.error)
            }

            val broker = RecoveryCoordinator.reconcile(appContext, "APP_OPEN", force = true)
            val readiness = tradeReviewGateway.readiness()
            _state.value = _state.value.copy(
                calls = callLedger.load(),
                brokerTruth = broker ?: brokerStore.load(),
                liveTradingReadiness = readiness
            )

            val activePlan = plan ?: cachedPlan
            if (activePlan != null) {
                val scan = signalScanner.scan(activePlan)
                _state.value = _state.value.copy(
                    signalScan = scan,
                    calls = callLedger.load(),
                    lastError = scan.errors.firstOrNull() ?: _state.value.lastError
                )
            }
        }

        _state.value = _state.value.copy(
            initialized = true,
            isRefreshing = false,
            calls = callLedger.load(),
            strategySummary = strategyStore.summary(),
            replaySummary = strategyStore.lastReplay()
        )
    }

    suspend fun refreshResearch(force: Boolean = true) = mutex.withLock {
        _state.value = _state.value.copy(isRefreshing = true, lastError = null)
        val (result, plan) = research.refreshPlan(force)
        _state.value = _state.value.copy(
            researchPlan = plan ?: _state.value.researchPlan,
            researchSources = research.sourceStatuses(),
            usingCachedResearch = research.sourceStatuses().any { it.usingCachedData },
            isRefreshing = false,
            lastError = if (result.ok) null else result.error
        )
    }

    suspend fun refreshSignals() = mutex.withLock {
        val plan = _state.value.researchPlan
        if (plan == null) {
            _state.value = _state.value.copy(lastError = "Research plan is not ready.")
            return@withLock
        }
        _state.value = _state.value.copy(isRefreshing = true, lastError = null)
        val scan = signalScanner.scan(plan)
        _state.value = _state.value.copy(
            signalScan = scan,
            calls = callLedger.load(),
            isRefreshing = false,
            lastError = scan.errors.firstOrNull()
        )
    }

    suspend fun refreshAll() {
        refreshResearch(force = true)
        refreshSignals()
        refreshBrokerTruth(force = false)
    }

    suspend fun runShadowReplay() = mutex.withLock {
        val plan = _state.value.researchPlan
        if (plan == null) {
            _state.value = _state.value.copy(lastError = "Research plan is not ready for replay.")
            return@withLock
        }
        _state.value = _state.value.copy(isReplaying = true, lastError = null)
        val summary = replayEngine.runFull(plan)
        _state.value = _state.value.copy(
            replaySummary = summary,
            strategySummary = strategyStore.summary(),
            isReplaying = false,
            lastError = summary.errors.firstOrNull()
        )
    }

    suspend fun refreshBrokerTruth(force: Boolean = true) = mutex.withLock {
        _state.value = _state.value.copy(isRefreshing = true, lastError = null)
        val broker = RecoveryCoordinator.reconcile(appContext, "USER_REFRESH", force)
        _state.value = _state.value.copy(
            calls = callLedger.load(),
            brokerTruth = broker ?: brokerStore.load(),
            isRefreshing = false,
            lastError = broker?.error
        )
    }

    fun saveTradeBudget(value: Int) {
        val settings = tradeReviewStore.saveBudget(value)
        _state.value = _state.value.copy(tradeReviewSettings = settings)
        AppAudit.log(
            appContext,
            "TRADE_REVIEW_BUDGET_CHANGED",
            org.json.JSONObject().put("budget_rupees", settings.budgetRupees)
        )
    }

    fun saveLiveReviewMode(enabled: Boolean) {
        val settings = tradeReviewStore.saveLiveReviewMode(enabled)
        _state.value = _state.value.copy(tradeReviewSettings = settings)
        AppAudit.log(
            appContext,
            "LIVE_REVIEW_MODE_CHANGED",
            org.json.JSONObject().put("enabled", settings.liveReviewMode)
        )
    }

    suspend fun previewOrder(call: RecommendationCall): OrderReviewResult {
        val result = tradeReviewGateway.review(call)
        AppAudit.log(
            appContext,
            "ORDER_REVIEW_PREFLIGHT",
            org.json.JSONObject()
                .put("call_id", call.callId)
                .put("symbol", result.tradingSymbol)
                .put("side", result.transactionType)
                .put("product", result.product)
                .put("quantity", result.quantity)
                .put("budget_rupees", result.budgetRupees)
                .put("required_margin", result.requiredMargin)
                .put("available_balance", result.availableBalance)
                .put("ready", result.ready)
                .put("blockers", org.json.JSONArray(result.blockers))
        )
        return result
    }

    suspend fun refreshLiveTradingReadiness() = mutex.withLock {
        _state.value = _state.value.copy(isRefreshing = true, lastError = null)
        val (validationResult, validation) = groww.validate()
        val readiness = tradeReviewGateway.readiness()
        _state.value = _state.value.copy(
            validation = validation?.copy(liveExecutionReady = false) ?: _state.value.validation,
            liveTradingReadiness = readiness,
            lastValidatedAtMillis = groww.lastValidationAtMillis(),
            isRefreshing = false,
            lastError = readiness.brokerMessage ?: if (validationResult.ok) null else validationResult.error
        )
        AppAudit.log(
            appContext,
            "GROWW_TRADING_READINESS",
            org.json.JSONObject()
                .put("broker_ready", readiness.brokerReady)
                .put("groww_auth_ok", readiness.growwAuthOk)
                .put("static_ip_matches", readiness.staticIpMatches)
                .put("static_ip_confirmed", readiness.staticIpConfirmed)
                .put("nse_enabled", readiness.nseEnabled)
                .put("cash_segment_enabled", readiness.cashSegmentEnabled)
                .put("ddpi_enabled", readiness.ddpiEnabled)
                .put("blockers", org.json.JSONArray(readiness.blockers))
        )
    }

    suspend fun validateGrowwAndStaticIp() = mutex.withLock {
        _state.value = _state.value.copy(isRefreshing = true, lastError = null)
        val (result, value) = groww.validate()
        val (_, status) = groww.fetchStatus()
        _state.value = _state.value.copy(
            connectionStatus = status ?: _state.value.connectionStatus,
            validation = value?.copy(liveExecutionReady = false) ?: _state.value.validation,
            lastValidatedAtMillis = groww.lastValidationAtMillis(),
            isRefreshing = false,
            lastError = if (result.ok) null else result.error
        )
    }

    suspend fun saveGrowwSettings(
        token: String,
        secret: String,
        expectedStaticIp: String,
        whitelistConfirmed: Boolean
    ): ApiResult = mutex.withLock {
        _state.value = _state.value.copy(isRefreshing = true, lastError = null)
        val result = groww.saveGrowwSettings(token, secret, expectedStaticIp, whitelistConfirmed)
        val (_, status) = groww.fetchStatus()
        _state.value = _state.value.copy(
            connectionStatus = status ?: _state.value.connectionStatus,
            isRefreshing = false,
            lastError = if (result.ok) null else result.error
        )
        result
    }

    companion object {
        @Volatile private var instance: AppStateRepository? = null

        fun get(context: Context): AppStateRepository =
            instance ?: synchronized(this) {
                instance ?: AppStateRepository(context).also { instance = it }
            }
    }
}
