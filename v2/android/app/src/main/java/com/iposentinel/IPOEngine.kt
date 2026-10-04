package com.iposentinel

/**
 * IPO lifecycle engine.
 *
 * State progression is intentionally explicit:
 * DISCOVERED -> NSE_VERIFIED -> SYMBOL_CONFIRMED -> GROWW_MATCHED -> D1_D30_TRACKING
 */
class IPOEngine {
    enum class Lifecycle {
        DISCOVERED,
        NSE_VERIFIED,
        SYMBOL_CONFIRMED,
        GROWW_MATCHED,
        D1_D30_TRACKING
    }

    fun advance(candidate: IPOCandidate, next: Lifecycle): IPOCandidate {
        return candidate.copy(stage = next)
    }
}

data class IPOCandidate(
    val companyName: String,
    val symbol: String? = null,
    val stage: IPOEngine.Lifecycle = IPOEngine.Lifecycle.DISCOVERED
)
