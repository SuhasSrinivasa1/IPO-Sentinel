# IPO Sentinel Architecture — v1.4.2

## Core rule

Research, signal generation, replay evidence and broker state are different layers. A research candidate is never promoted to a Call merely because its symbol was discovered.

```text
NSE discovery / identity
        |
DirectResearchClient
        |
READY_FOR_RESEARCH
        |
exact NSE ↔ Groww identity
        |
DirectMarketDataClient ----> local compressed OHLCV archive
        |
AdaptiveStrategyEngine
        |
timestamped SignalDecision
        |
CallLedgerStore
        |
LIVE <-------------------------------> CLOSED
        |
broker truth / candle reconciliation
```

`AppStateRepository` is the shared application state consumed by Calls, Research, Strategies and System.

## Calls-first UX

The first/default bottom tab is **Calls**. A compact top segmented control switches between **LIVE** and **CLOSED**.

The call list is intentionally information-dense. Shadow P&L and Closed P&L appear above Live / Closed.

Each call row includes:
- official NSE symbol plus the exact Groww symbol visible on the collapsed row;
- verified identity status;
- signal/close time in IST;
- strategy;
- entry/stop/targets;
- current or realized return;
- expandable strategy evidence and broker reconciliation.

Research candidates live exclusively in the Research tab, including a dedicated **Identity pending** section for unresolved mappings.

## Signal evidence

A signal requires:
1. final NSE listing identity;
2. exact Groww NSE/CASH mapping;
3. real 5-minute OHLCV candles;
4. a composite strategy threshold.

The engine records point-in-time inputs used by the signal: candle pattern, volume ratio, VWAP, VWAP distance, ATR, opening range, NIFTY relative strength, listing gap when issue price is parseable, subscription multiple, board and trading-day number.

No future candle is used in a live signal decision.

## Strategy architecture

The five v1.4.2 strategies are combinations:
- Listing Momentum Consensus;
- VWAP Reclaim + Absorption;
- Breakout Retest Continuation;
- Compression → Expansion;
- Relative Strength Continuation.

`StrategyEvidenceStore` owns evidence. Static strategy definitions do not imply success.

Evidence statuses:
- CHAMPION: >=20 replay trades + expectancy/PF/drawdown thresholds;
- CHALLENGER: >=10 trades + positive expectancy + PF threshold;
- LEARNING: insufficient evidence.

## Shadow execution policy

The model portfolio uses ₹1,00,000 starting capital, a ₹25,000 per-position cap and ₹1,000 modeled risk budget. Position quantity is determined from both available notional and stop distance.

Strategy-specific hold limits are part of the execution decision: 1, 2, 5, 10 or 20 trading sessions for the five strategy families respectively. Stop/Target 2 can exit earlier. This lets the same signal framework model intraday, short swing and multi-week holds without user intervention.

## Shadow replay

`ShadowReplayEngine` iterates historical candles sequentially. At each replay bar, the strategy engine sees only candles up to that bar. Exits are simulated only from subsequent candles, including later trading sessions when the selected strategy permits a multi-day hold.

When a stop and target are both contained within the same candle and tick ordering is unavailable, replay chooses STOP. This is intentionally conservative.

Missed-opportunity analysis records sessions with >=4% subsequent upside and no emitted strategy signal, together with contemporaneous blockers.

## Candle archive

All successfully fetched Groww OHLCV is grouped by symbol/date and stored under app-private storage as compressed JSON. The archive is evidence, not a public cache.

This supports:
- post-mortem reconstruction;
- repeatable feature extraction;
- future strategy-regression tests;
- reduced dependence on source availability at audit time.

## Daily learning schedule

Three WorkManager loops complement the foreground app:
- network-constrained market scan every 15 minutes;
- daily pre-market research refresh targeting 08:35 IST;
- daily off-market research/shadow replay targeting 18:45 IST.

The foreground app scans every five minutes while open. Worker timing remains best-effort because Android/OEM schedulers may defer background work.

## Research source health

The NSE direct client keeps each source independently FRESH, CACHED or FAILED. Cached discovery/identity can keep known identities visible, but it cannot itself create a signal.

This distinction is critical when NSE challenges the device with 403/HTML responses.

## Groww token lifecycle

Stored TOTP credentials are Keystore-encrypted. `DirectGrowwClient` reuses an access token for no more than 30 minutes and then regenerates it; broker 401/403 responses force immediate re-authentication. Broker-required account approvals remain outside the app's control.

## Broker truth / OEM recovery

`BrokerNotificationListenerService` is a wake source. It does not parse notification text as authoritative fill state.

`RecoveryCoordinator` re-reads Groww order list and CASH positions and reconciles durable calls. Recovery is scheduled through listener reconnect, boot/package replacement, app open, periodic WorkManager and user refresh.

`LiveSignalScanner` also catches up outstanding signal calls by retrieving candle history from the signal date through the current date, so an OEM-killed process can reconstruct stop/target/session-close outcomes after restart.

## Data not yet promoted into strategy gates

Broad news sentiment and deep prospectus financial-statement features are intentionally not present in v1.4.2 strategy scores. They require a timestamped, reproducible point-in-time source. Using current articles/fundamentals to explain old candles would introduce look-ahead bias.

The architecture can add a future `PointInTimeContextProvider` beneath the replay engine once such a source is available.

## Security and execution

`ValidationStatus.liveExecutionReady` and `AppState.executionReady` remain false.

v1.4.2 generates and evaluates shadow calls. It does not place, modify or cancel real-money orders.

