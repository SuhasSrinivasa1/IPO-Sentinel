# IPO Sentinel

IPO Sentinel is an Android-first IPO signal-research application for NSE listings. v1.4.3 separates **research candidates** from **actual calls**: a company name or READY_FOR_RESEARCH row is not a call. A call requires an exact official NSE identity, an exact Groww NSE/CASH instrument, and a timestamped composite-strategy trigger from real market candles.

## Current release target

- Version: **1.4.1**
- Version code: **143**
- Android application ID: `com.suhas.iposentinel.installfix`
- Artifact: `IPO-Sentinel-v1.4.3-PRO-debug.apk`
- Compile / target SDK: 35
- Java: 17
- Automatic real-money execution: **LOCKED OFF**

## Product layout

The bottom navigation is deliberately small:

1. **Calls** — default/first tab, with **LIVE** and **CLOSED** sections at the top.
2. **Research** — upcoming IPOs, D1-D30 verified universe, exact NSE/Groww identity, source health.
3. **Strategies** — five composite playbooks, shadow-replay evidence, missed-opportunity diagnostics.
4. **System** — Groww credentials/static IP, NSE/Groww health, recovery, notifications, audit export.

The UI uses a dense, flat market-terminal hierarchy rather than card-heavy boxes. Calls emphasize symbol, state, strategy, price levels, timestamp and outcome. Tapping a call expands its identity and evidence.

## Call contract

A `RecommendationCall` may be created only after all of the following:

```text
Official NSE listing identity
        |
exact NSE symbol (+ ISIN when available)
        |
exact Groww NSE/CASH instrument
        |
real Groww 5-minute OHLCV candles
        |
one or more composite strategies pass
        |
timestamped LIVE shadow call
```

Legacy `research:` call rows from v1.3.3 are removed during migration. Research refreshes cannot create calls.

Each call persists:
- official NSE symbol, ISIN and exact Groww symbol;
- shadow quantity, deployed notional, rupee P&L and selected holding policy;
- signal timestamp and update timestamp;
- primary strategy plus confirming strategies;
- signal score and evidence;
- entry, stop, T1 and T2 derived at signal time;
- candle pattern, volume ratio, VWAP and NIFTY relative-strength context;
- current/exit price and return when market evidence is available;
- close timestamp and reason;
- broker-reconciliation metadata when present.

## Five composite strategies

The strategy lab deliberately uses combinations rather than single indicators:

1. **Listing Momentum Consensus** — opening-range breakout + VWAP acceptance + volume acceleration + ATR + listing context.
2. **VWAP Reclaim + Absorption** — below-VWAP rejection + reclaim + lower-wick absorption + renewed volume.
3. **Breakout Retest Continuation** — positive trend + VWAP hold + retest/higher-low structure + volume re-expansion.
4. **Compression → Expansion** — tight range + volume dry-up + breakout + ATR + VWAP.
5. **Relative Strength Continuation** — NIFTY relative strength + VWAP + higher closes + short-term breakout + volume.

These are hypotheses until replay evidence exists. The app does not label a strategy as working simply because it is defined.

Runtime classifications are evidence-gated:
- **CHAMPION**: at least 20 replay trades, expectancy >= 35 bps, profit factor >= 1.25, max drawdown <= 900 bps.
- **CHALLENGER**: at least 10 replay trades, positive expectancy and profit factor >= 1.05.
- otherwise **LEARNING**.

## Shadow account, P&L and holding decisions

The Calls tab shows **Shadow P&L** and **Closed P&L** immediately above Live / Closed. The model account starts at **₹1,00,000**. New shadow positions are sized fail-closed using both a **₹25,000 maximum position** and **₹1,000 maximum modeled risk per trade**, subject to remaining shadow capital.

The application chooses the hold horizon from the strategy rather than forcing every call to close intraday:
- Listing Momentum Consensus: intraday;
- VWAP Reclaim + Absorption: up to 2 trading sessions;
- Breakout Retest Continuation: up to 5 trading sessions;
- Compression → Expansion: up to 10 trading sessions;
- Relative Strength Continuation: up to 20 trading sessions.

Stop loss and Target 2 remain hard shadow exits. If neither is reached, the strategy-specific maximum hold closes the position at the last available session close. Multi-session calls are reconciled from Groww candle history after process death.

Groww broker positions remain read-only. The app also reads Groww's `realised_pnl` field when available so actual broker realized P&L is displayed separately from the hypothetical shadow result.

## Groww authentication lifecycle

TOTP credentials are encrypted in Android Keystore. The Android client caches a generated Groww access token for at most 30 minutes, then regenerates it using the saved TOTP token/secret; HTTP 401/403 responses trigger an immediate forced refresh. Manual Groww access tokens have a broker-defined daily expiry, but the app does not require the user to re-enter the rotating six-digit TOTP code.

## Shadow replay and daily learning

`ShadowReplayEngine` replays exact-identity IPOs bar-by-bar with no future data available to the signal decision. It tests up to the first 30 trading sessions after listing.

Replay records:
- strategy;
- signal time;
- entry/exit;
- exit reason;
- net return in bps;
- maximum favorable excursion;
- maximum adverse excursion.

If both stop and target occur inside the same 5-minute candle, replay assumes the stop was hit first. This intentionally avoids optimistic intrabar hindsight.

Sessions with no signal but >=4% subsequent upside are recorded as **missed opportunities** with blockers such as weak volume, below VWAP, no opening-range break, weak relative strength or non-rising short-term structure.

The Strategies tab ranks only persisted replay evidence; it never invents win rates.

## Market data and reproducibility

`DirectMarketDataClient` uses the authenticated Groww historical-candles API for 5-minute NSE/CASH data and NIFTY benchmark context.

Every successfully fetched OHLCV session is archived locally as compressed JSON under application-private storage. This provides reproducible evidence for later audits and avoids relying only on a future re-fetch.

While the app is active, the signal scanner runs on a five-minute cadence. WorkManager also schedules a network-constrained market scan every 15 minutes as a process-death fallback.

## Off-market research

WorkManager schedules two daily research anchors: an **08:35 IST pre-market refresh** and an **18:45 IST off-market research/replay cycle**. The pre-market pass refreshes the IPO universe, exact identities and signal readiness. The 18:45 pass:
- refreshes the IPO research plan;
- replays exact verified IPO identities;
- updates strategy evidence;
- records missed moves and blockers;
- leaves automatic execution locked off.

WorkManager timing is best-effort under Android/OEM power management, not an exact wall-clock guarantee.

## Exact NSE identity

The broker identity boundary remains fail-closed:

```text
DISCOVERED
  -> IDENTITY_VERIFIED
  -> GROWW_SYMBOL_VERIFIED
  -> READY_FOR_RESEARCH
```

A live scan additionally requires `growwSymbol == "NSE-" + officialNseSymbol`. Fuzzy company-name matching cannot generate a call.

## Source resilience

Official NSE endpoints can return access-denied/challenge responses. `DirectResearchClient` keeps per-source FRESH/CACHED/FAILED state and preserves last-known-good identity data instead of converting a source failure into “no IPOs.”

Once an IPO identity has been exactly resolved, intraday signal generation is driven from Groww candle data rather than pretending cached NSE discovery data is a live price signal.

## Fundamentals and news boundary

v1.4.3 includes IPO/listing context available in the current direct research path, including board, listing day, issue-price text and subscription multiple when supplied by the source. It does **not** yet use broad news sentiment or full prospectus financial-statement history as a strategy gate.

Those features require a reliable timestamped source with point-in-time availability. Adding present-day news/fundamentals retrospectively to old candles would contaminate shadow replay with hindsight, so the app fails closed rather than manufacturing that context.

## Process-death recovery

Durable state is not owned by the notification listener or activity.

Recovery paths include:
- notification-listener reconnect;
- Groww notification wake hint;
- boot;
- package replacement;
- app open;
- broker-truth WorkManager;
- market-signal WorkManager;
- manual refresh.

The signal scanner catches up outstanding calls by fetching the full candle range from the original signal date through the current date after a process gap. Broker notifications are wake hints only; read-only Groww orders/positions remain broker truth.

## Audit

Weekly verification export contains:
- app audit JSONL;
- weekly summary;
- research-source health;
- full call ledger with signal evidence;
- strategy evidence;
- shadow-replay summary/missed moves;
- broker-truth status.

Credentials and access tokens are excluded.

## Security / execution boundary

- HTTPS-only active networking;
- `android:usesCleartextTraffic="false"`;
- Android Keystore-backed credential encryption;
- no custom backend URL/device key in active Android flows;
- no secrets/signing keys committed;
- historical `BackendApi.kt` is not used by active Android paths;
- automatic order placement, modification and cancellation remain **disabled**.

## CI

The release workflow verifies the Calls-first architecture, five strategy IDs, direct Groww candle path, exact symbol guard, shadow replay, off-market workers, migration away from research-as-call semantics, recovery components, locked execution, lint, Kotlin compilation and APK assembly.



## Live broker order review

v1.4.3 adds an in-app `TradeReviewGateway` between a strategy call and Groww. It is a read-only pre-trade layer.

From a LIVE call, tapping the confidence percentage:
- derives the exact NSE trading symbol from the verified call identity;
- maps BUY to CASH/CNC and SELL to CASH/MIS;
- sizes quantity from the configured per-order budget;
- validates that the market reference price is fresh;
- reads Groww available equity balance;
- asks Groww's margin API for the exact proposed order requirement;
- blocks insufficient balance or stale/ambiguous orders;
- generates an immutable order reference and broker-ready order summary.

Settings includes a per-order budget from ₹1,000 to ₹1,00,000 in ₹1,000 increments.

The app intentionally does **not** call Groww's order-create, modify or cancel endpoints. The CI architecture gate rejects those endpoints if they appear in active Android code. The generated review can be copied for explicit broker confirmation.

This keeps signal generation, order construction and broker submission separate and auditable.
