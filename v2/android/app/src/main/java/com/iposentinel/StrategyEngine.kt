package com.iposentinel

/** Strategy framework. Evidence is populated only from validated data. */
class StrategyEngine {
    private val strategies = listOf(
        "Momentum",
        "Breakout",
        "Gap Analysis",
        "Opening Range",
        "Volume Expansion",
        "VWAP",
        "Relative Strength",
        "Mean Reversion",
        "Trend Following",
        "IPO Momentum",
        "IPO Liquidity",
        "IPO Risk Adjusted",
        "IPO First 5 Days",
        "IPO D1-D30",
        "Subscription Strength",
        "Sector Strength",
        "Market Regime",
        "Volatility Filter",
        "Capital Protection"
    )

    fun catalog(): List<StrategyResult> = strategies.map {
        StrategyResult(it, 0.0, false)
    }
}
