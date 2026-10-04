# IPO Sentinel

IPO Sentinel is an isolated Android + backend project for research, shadow trading, and eventually controlled execution around newly listed NSE cash equities.

## Core contract

- Discover IPOs that are scheduled to list on the next NSE trading day.
- Run an after-hours research job after the cash session closes.
- Observe the special pre-open/listing process and continuous trading session.
- Continue monitoring every newly listed IPO for its first **30 exchange trading days** for secondary opportunities.
- Produce one of: WAIT, PROBE_LONG, BUILD_LONG, HOLD_LONG, REDUCE_LONG, FLAT, PROBE_SHORT, BUILD_SHORT, HOLD_SHORT, COVER_SHORT.
- Long positions may persist as delivery when the thesis remains valid.
- Short positions are intraday only and require live broker/exchange eligibility.
- Only positions/orders created by IPO Sentinel are managed by IPO Sentinel.
- Shadow mode is the default. Shadow capital defaults to INR 100,000.
- Live budget is user-selectable from INR 10,000 to INR 100,000.
- Live execution is an explicit user toggle and is OFF by default.
- Broker credentials are never stored in the APK or committed to Git.

## Architecture

Android (Kotlin/Jetpack Compose) is the control surface. A static-IP backend performs broker authentication, live market-data processing, strategy evaluation, position reconciliation, replay, and order routing.

The first implementation is deliberately split into:

1. **Discovery & research** — next-listing calendar, issue/fundamental data, market/sector context.
2. **Listing-session intelligence** — special pre-open equilibrium data, 1m/3m/5m/15m bars, VWAP, RVOL, depth, spread, order-flow, circuit proximity.
3. **30-day post-listing monitor** — keeps each IPO active for D1-D30 trading days and looks for continuation, healthy pullback, anchored-VWAP reclaim, post-IPO base breakout, failed breakdown/reclaim, volume revival, and eligible intraday fade opportunities.
4. **Decision engine** — regime classification + compatible strategy ensemble.
5. **Risk/execution engine** — broker eligibility, margin, liquidity, slippage, idempotent orders, OCO/exit logic.
6. **Owned-position registry** — isolates IPO Sentinel trades from every unrelated portfolio holding.
7. **Replay & learning** — exact point-in-time replay, MFE/MAE, missed opportunity, exit quality, strategy attribution, champion/challenger promotion.
8. **Shadow ledger** — daily and cumulative net P&L for a fixed virtual capital amount.

## 30-trading-day lifecycle

A listing stays in the active research universe for 30 actual exchange trading days, not 30 calendar days. Weekends and official exchange holidays do not consume the monitoring window.

The engine uses different opportunity families by age:

- **D1-D5:** post-listing continuation, failed listing-day move, VWAP/anchored-VWAP behavior and liquidity normalization.
- **D2-D10:** first healthy pullback, reclaim after shakeout, renewed relative strength.
- **D5-D30:** post-IPO base breakout, volume revival, failed breakdown/reclaim and trend continuation.
- **D1-D30 bearish:** bearish evidence can be tracked every day, but cash short execution is intraday-only and still requires current Groww/exchange eligibility.

Every 30-day decision is also replayed in the INR 100,000 shadow account so the application learns whether listing-day, early-post-listing, or later-base opportunities have the best net expectancy.

## Listing-day timing assumption

IPO Sentinel must treat the listing session as a special market state. For NSE IPO listings, the special pre-open session precedes normal trading. Continuous trading should only be enabled after the exchange transitions the symbol into the normal market session. The backend validates this state from current exchange/broker data rather than relying on a hard-coded clock alone.

## Development phases

- Phase 0: data-only discovery + three-month backfill + UI.
- Phase 1: full shadow engine, D1-D30 monitor and replay.
- Phase 2: one-symbol canary with tiny live quantity.
- Phase 3: controlled scaling up to the user-selected budget.
- Phase 4: adaptive champion/challenger strategy weighting.

See `docs/ARCHITECTURE.md` and `docs/RESEARCH_PLAN.md`.


## Groww settings (v0.4)

The Android UI deliberately hides service-transport details. There is no user-facing backend URL, HTTP/HTTPS field, or admin-key field.

The Settings tab contains only the trading inputs the user actually needs:

- Groww TOTP token / API key
- Groww TOTP secret
- Whitelisted static public IP
- Confirmation that the static IP has been whitelisted in Groww

The Dashboard no longer duplicates Settings with a separate "Configure Groww Connection" button.

### Internal service configuration

The static-IP trading service remains part of the architecture because API order placement must originate from the fixed whitelisted public IP. Its endpoint and device key are deployment/build configuration, not user settings.

Android build variables:
- `IPO_SENTINEL_API_URL`
- `IPO_SENTINEL_DEVICE_KEY`

Trading-service environment:
- `IPO_SENTINEL_DEVICE_KEY`
- `IPO_SENTINEL_MASTER_KEY`
- optional `IPO_SENTINEL_SETTINGS_FILE`

The TOTP token and secret are never returned by the settings APIs. They are encrypted at rest using the master key.

### User flow

1. Open **Settings**.
2. Enter Groww TOTP token/API key and TOTP secret.
3. Enter the fixed static public IP whitelisted in Groww.
4. Confirm the Groww whitelist checkbox.
5. Tap **Save Groww Settings**.
6. Tap **Validate Groww + Static IP**.
7. Live auto-trading remains locked until Groww authentication and the static-IP checks pass.


## Stable Android behavior (v1.0.0)

IPO Sentinel v1.0.0 consolidates the control application into three tabs: **Dashboard**, **Strategies**, and **Settings**.

### Notifications

- Android 13+ requests the standard `POST_NOTIFICATIONS` permission on first launch.
- IPO Sentinel does **not** request Notification Listener access and does not read notifications from other applications.
- Live mode starts a foreground order-event monitor so the user can receive order lifecycle notifications while the market session is active.
- Supported lifecycle notifications include order submission, broker acknowledgement, partial/complete fill, exit submission, position closed, rejection/cancellation, force-flat, and risk halt.
- A **Send Test Notification** button is available in Settings.
- The backend order gateway emits lifecycle events before submission and as Groww order state changes.

### Weekly audit export

Settings includes **Export Weekly Logs**. It creates a ZIP containing:

- `app-audit.jsonl` — Android-side state changes and validation events.
- `backend-audit.jsonl` — server-side decisions, Groww validation, live-state changes, order lifecycle events and related audit entries when the service is reachable.
- `metadata.json` — version, export time and audit period.

Groww TOTP/API secrets are intentionally excluded from audit exports.

### Strategies offline behavior

The APK contains the registered 19-family strategy catalog. If the static-IP trading service has not yet been provisioned, the Strategies tab remains usable and shows the catalog with zero tested/champion counts rather than showing the whole screen as unavailable. Replay evidence replaces the local zero-state automatically when the backend becomes reachable.

### Live state

Live trading state is server-authoritative. Enabling live mode requires Groww authentication and static-IP validation to pass. The selected budget is locked while live mode is armed. The Android foreground monitor is started only after the server acknowledges the live state.

### Build validation

The stable GitHub workflow compiles and tests the IPO backend, runs its pytest suite and dependency check, runs Android lint, and only then builds the APK artifact.


## Final autonomous research release (v1.1.0)

This release freezes the automated IPO-discovery and listing-day identity workflow.

- Research runs independently of Groww authentication and refreshes again immediately after a successful Groww validation.
- A full NSE IPO research refresh runs every calendar day at 16:05 IST, including Saturdays and Sundays, plus at backend startup.
- Weekdays revalidate at 08:30 IST and repeatedly through the new-listing special pre-open / continuous-market transition.
- IPO research does not require a trading symbol. Candidates can exist in a pre-symbol state using official issue identity, ISIN when available, company identity and issue dates.
- Live identity is authorized only after the official NSE forthcoming-listing source provides the listing symbol/date and the Groww NSE CASH instrument resolves exactly.
- When ISIN is available, both NSE trading symbol and ISIN must agree exactly; ambiguous or conflicting rows fail closed.
- Groww instrument metadata is rechecked for exchange token, series, lot size, tick size, freeze quantity, buy/sell permission and live availability.
- Listing-day auto execution is disabled during NSE special pre-open. The engine may observe 09:00-10:00, but continuous-market orders are not eligible before 10:00 IST and still require fresh quote, market depth, liquidity, spread, impact, circuit, position and order-state gates.
- The dashboard exposes daily research health, next-trading-day candidates, next-week candidates, NSE identity confirmation and Groww resolution state.


## Trader-audit hardening release (v1.1.1)

This release keeps the v1.1.0 autonomous IPO research and exact-identity workflow and adds execution hardening based on a listing-day trader audit:

- Fast NSE/Groww identity rechecks continue through 11:00 IST so delayed broker instrument publication does not leave the listing watch stale after 10:15.
- Listing-day live quotes must have a last trade no older than 15 seconds; D2-D30 execution uses a 30-second maximum.
- The order gateway independently derives the live spread from Groww bid/offer/depth and uses the more conservative value when an upstream strategy also supplies a spread estimate.
- The gateway estimates requested-quantity book impact from the live opposing depth and blocks when visible depth cannot absorb the order or estimated impact is excessive.
- Groww freeze quantity and tick size are enforced before submission.
- Unknown product strings are rejected instead of silently falling back to CNC.
- During 10:00-10:05 on listing day, new MARKET entries are blocked; price-controlled orders may proceed only if all identity, quote, depth, liquidity, spread, impact, circuit, reconciliation, position-isolation and budget gates pass.
- The displayed 09:00-09:45 special-pre-open rule now matches the execution policy: IPO Sentinel observes that phase and does not auto-submit continuous-market orders.


## Settings and provisioning stability release (v1.1.2)

- Unsaved Groww token and TOTP-secret edits remain in memory while switching between Dashboard, Strategies and Settings.
- Static-IP draft and whitelist confirmation persist locally across tab changes and app restarts.
- Successfully saved credentials remain backend-only and are intentionally not repopulated into Android fields; the UI now shows a clear saved-state explanation.
- Saving Groww settings now automatically triggers validation so readiness updates immediately.
- Settings now displays explicit trading-service provisioning state (HTTPS endpoint and device authentication) instead of the ambiguous generic error.
- Live trading still fails closed if the APK has not been built with a real HTTPS trading-service endpoint and device key. The application does not fabricate or fall back to direct device-side broker execution.


## IPO intelligence and managed-trading release (v1.2.0)

- Fresh installations seed the active universe from official NSE recent listings as well as current/forthcoming issues, then maintain the listing-day through D30 opportunity set.
- The ₹5,000 daily objective is calculated only from IPO Sentinel's own reconciled broker fills. Groww account-level P&L and unrelated user holdings are excluded.
- Exact NSE/Groww-resolved D1-D30 candidates are scanned each market minute using price/VWAP, relative volume, first-five-minute structure, spread, order-book quantities and circuit distance.
- READY signals require an estimated post-cost edge of at least 0.5% of the configured budget and create Android signal notifications.
- Research cards show direction, entry, T1/T2, stop, quantity, confidence, RVOL and reasons. Card BUY is CNC/delivery; fresh SHORT is MIS only.
- Manual card orders work with auto mode OFF but still require static-IP, official identity, exact broker instrument, market-depth, circuit, order-state and position-isolation gates.
- When auto mode is armed, guarded READY signals can submit entries. App-owned positions remain managed after entry; T1 can take one 50% partial and T2/stop can close the remainder.
- IPO Sentinel registered orders and app-owned positions are reconciled every 30 seconds independently of the auto-entry switch.
- The Android Research tab exposes Top 3, tomorrow's queue, active D1-D30 universe, signals, managed positions, partial exits, closed WIN/LOSS calls, daily objective progress and learning history.
- Daily after-market review records outcomes and weak strategy families. Sunday revalidation retains proven CHAMPION families and flags repeatedly negative families for rework/demotion.

Live execution remains fail-closed: research may use broader discovery inputs, but no order is authorized from a guessed ticker, third-party symbol match, stale quote, unresolved Groww instrument, uncertain broker state or unrelated portfolio inventory.


## v1.2.1 install-fix package

This release intentionally uses Android applicationId `com.suhas.iposentinel.installfix`.
It exists to bypass stale multi-user/work-profile package records for the earlier
`com.suhas.iposentinel` debug-signed builds. Trading/research behavior is unchanged
from v1.2.0. Future production releases should return to a stable package identity only
after a persistent release-signing key is configured in CI.
