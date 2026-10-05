package com.suhas.iposentinel

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppState(
    val initialized: Boolean = false,
    val isRefreshing: Boolean = false,
    val connectionStatus: ConnectionStatus? = null,
    val validation: ValidationStatus? = null,
    val researchPlan: ResearchPlan? = null,
    val researchSources: List<ResearchSourceStatus> = emptyList(),
    val usingCachedResearch: Boolean = false,
    val strategySummary: StrategySummary = LocalStrategyCatalog.summary(),
    val lastValidatedAtMillis: Long? = null,
    val lastError: String? = null
) {
    val growwConnectionReady: Boolean
        get() = validation?.let {
            it.growwAuthOk && it.staticIpMatches && it.staticIpConfirmed && it.secretStoreReady
        } == true

    val executionReady: Boolean
        get() = false
}

class AppStateRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val groww = DirectGrowwClient(appContext)
    private val research = DirectResearchClient(appContext)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        AppState(strategySummary = LocalStrategyCatalog.summary())
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    fun snapshot(): AppState = _state.value

    suspend fun initialize() = mutex.withLock {
        if (_state.value.initialized || _state.value.isRefreshing) return@withLock

        val (_, status) = groww.fetchStatus()
        val cachedPlan = research.cachedPlan()
        _state.value = _state.value.copy(
            isRefreshing = true,
            connectionStatus = status,
            validation = groww.lastValidation()?.copy(liveExecutionReady = false),
            researchPlan = cachedPlan,
            researchSources = research.sourceStatuses(),
            usingCachedResearch = research.sourceStatuses().any { it.usingCachedData },
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
        }

        _state.value = _state.value.copy(
            initialized = true,
            isRefreshing = false,
            lastError = _state.value.lastError
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
