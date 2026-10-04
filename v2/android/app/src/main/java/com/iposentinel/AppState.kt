package com.iposentinel

/** Single source of truth for the application. */
data class AppState(
    val growwReady: Boolean = false,
    val nseHealthy: Boolean = false,
    val ipoUniverse: List<IpoCandidate> = emptyList(),
    val strategyResults: List<StrategyResult> = emptyList(),
    val executionAllowed: Boolean = false
)

data class IpoCandidate(
    val company: String,
    val symbol: String? = null,
    val lifecycle: String = "DISCOVERED"
)

data class StrategyResult(
    val name: String,
    val score: Double,
    val evidenceAvailable: Boolean = false
)
