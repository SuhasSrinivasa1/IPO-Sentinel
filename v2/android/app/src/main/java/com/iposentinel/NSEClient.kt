package com.iposentinel

/** Direct NSE research boundary. */
class NSEClient {
    fun lifecycleFor(company: String, symbol: String?): IpoCandidate {
        return IpoCandidate(
            company = company,
            symbol = symbol,
            lifecycle = if (symbol == null) "DISCOVERED" else "SYMBOL_VERIFIED"
        )
    }
}
