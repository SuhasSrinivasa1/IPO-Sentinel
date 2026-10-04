package com.iposentinel

/**
 * Central risk gate. All future execution requests must pass here.
 */
class RiskEngine {
    fun approve(request: TradeRequest): RiskDecision {
        if (request.quantity <= 0) {
            return RiskDecision(false, "Invalid quantity")
        }
        return RiskDecision(true, "Risk checks passed")
    }
}

data class TradeRequest(
    val symbol: String,
    val quantity: Int
)

data class RiskDecision(
    val approved: Boolean,
    val reason: String
)
