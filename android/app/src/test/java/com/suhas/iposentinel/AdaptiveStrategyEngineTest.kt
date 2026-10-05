package com.suhas.iposentinel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AdaptiveStrategyEngineTest {
    private val candidate = ResearchCandidate(
        candidateId = "TEST:IPO",
        lifecycleState = "READY_FOR_RESEARCH",
        symbol = "TESTIPO",
        companyName = "Test IPO Ltd",
        listingDate = "2026-10-05",
        issueStartDate = null,
        issueEndDate = null,
        officialIssueId = "1",
        isin = "INE000TEST01",
        board = "EQ",
        isSme = false,
        issueStatus = "LISTED",
        nseListingConfirmed = true,
        growwSymbol = "NSE-TESTIPO",
        growwSeries = "EQ",
        buyAllowed = true,
        sellAllowed = true,
        symbolResolved = true,
        growwResolutionStatus = "RESOLVED",
        resolutionStatus = "RESEARCH_D1_WAIT_LIVE_CONFIRMATION",
        issuePriceText = "100",
        subscriptionMultiple = 12.0,
        tradingDayNumber = 1,
        growwLotSize = 1
    )

    @Test
    fun listingMomentumNeedsRealConfirmationAndProducesLevels() {
        val start = Instant.parse("2026-10-05T03:45:00Z")
        val candles = listOf(
            candle(start, 100.0, 102.0, 99.5, 101.0, 1000),
            candle(start.plusSeconds(300), 101.0, 103.0, 100.5, 102.0, 1050),
            candle(start.plusSeconds(600), 102.0, 102.5, 100.8, 101.5, 900),
            candle(start.plusSeconds(900), 101.5, 102.4, 101.0, 102.0, 850),
            candle(start.plusSeconds(1200), 102.0, 102.6, 101.4, 102.2, 900),
            candle(start.plusSeconds(1500), 102.2, 102.8, 101.8, 102.5, 950),
            candle(start.plusSeconds(1800), 102.5, 110.0, 102.4, 109.0, 6000)
        )
        val signal = AdaptiveStrategyEngine().evaluateBest(candidate, candles)

        assertNotNull(signal)
        assertEquals("listing_momentum_consensus", signal!!.strategyId)
        assertEquals("BUY", signal.direction)
        assert(signal.stopLoss < signal.entryPrice)
        assert(signal.target1 > signal.entryPrice)
        assert(signal.target2 > signal.target1)
        assert(signal.evidence.volumeRatio > 1.35)
    }

    @Test
    fun tooFewCandlesNeverCreatesCallSignal() {
        val start = Instant.parse("2026-10-05T03:45:00Z")
        val candles = (0 until 6).map {
            candle(start.plusSeconds(it * 300L), 100.0, 101.0, 99.0, 100.5, 1000)
        }
        assertNull(AdaptiveStrategyEngine().evaluateBest(candidate, candles))
    }

    private fun candle(
        at: Instant,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long
    ) = MarketCandle(at, open, high, low, close, volume)
}
