# IPO Sentinel

IPO Sentinel is an Android-first IPO research and broker-reconciliation application for NSE listings. The production Android path communicates directly with Groww and official NSE sources over HTTPS. The historical Python backend remains in the repository for reference/tests, but active Android screens do not depend on it.

## Current release target

- Version: **1.3.3**
- Version code: **133**
- Android application ID: `com.suhas.iposentinel.installfix`
- Artifact: `IPO-Sentinel-v1.3.3-RECOVERY-debug.apk`
- Compile / target SDK: 35
- Java: 17

## v1.3.3 architecture

The Android application has one shared application-level source of truth:

```text
                 AppStateRepository
                        |
     -----------------------------------------
     |                 |                     |
DirectGrowwClient DirectResearchClient  CallLedgerStore
     |                 |                     |
 Groww auth       NSE + instrument data   LIVE / CLOSED
     |                 |                  timestamps
     |                 |                     |
     ----------- BrokerTruthClient ------------
                        |
        order list + positions (read only)
                        |
               RecoveryCoordinator
                        |
  listener reconnect / boot / app open / periodic work
                        |
      Home • Calls • Research • Strategy • Settings
```

Automatic order placement remains locked off. v1.3.3 adds durable call state and read-only broker reconciliation; it does not silently enable trading.

## Live and closed calls

The **Calls** screen is the durable recommendation ledger. Each call stores:

- first recommendation timestamp;
- last-updated timestamp;
- research-snapshot timestamp;
- LIVE or CLOSED state;
- close timestamp and reason when applicable;
- matching Groww order status/order ID when observed;
- matching Groww position quantity and average price when observed;
- last broker-reconciliation timestamp.

A recommendation is created only from a `READY_FOR_RESEARCH` candidate whose official NSE identity is resolved to an exact Groww NSE/CASH instrument. The app still does not fabricate entry, stop, target, confidence, or performance when live evidence is absent.

A degraded/cached NSE refresh does not close a call merely because a source temporarily disappears. Research-source failure and a genuine closed call remain separate states.

## Broker-truth recovery

Notification events are wake-up hints, not the source of truth. Recovery always re-reads Groww's read-only order-list and positions state and reconciles that state with the local durable call ledger.

Recovery can be triggered by:

- notification-listener connection/reconnection;
- a Groww notification;
- device boot;
- application package replacement/update;
- application open;
- periodic WorkManager execution while network is available;
- the manual **Reconcile From Groww Now** action.

This means an unfinished call remains stored if Vivo/Funtouch or Android kills the listener/process. When a supported recovery trigger runs again, the app resumes reconciliation from persisted state plus broker truth.

The app only closes a call from broker reconciliation after it previously observed a non-zero broker position for that call and subsequently observes the position flat. This avoids treating a temporarily missing/failed broker response as a close.

## Groww authentication and secrets

Groww authentication is direct from Android using the Groww TOTP token/API key plus a locally generated TOTP. The token, TOTP secret, and generated access token are encrypted using an Android Keystore-backed AES/GCM key.

The recovery path reuses a recent encrypted access token and refreshes it after broker 401/403 responses, reducing unnecessary TOTP authentication calls.

The app does **not** accept a custom trading-service URL, device ID, device key, backend endpoint, or cleartext HTTP endpoint. Stored secrets are never repopulated into visible Settings fields.

## IPO identity lifecycle

```text
Official NSE discovery
        |
    DISCOVERED
        |
Official listing symbol/date/ISIN
        |
 IDENTITY_VERIFIED
        |
Exact Groww NSE/CASH instrument match
        |
 GROWW_SYMBOL_VERIFIED
        |
Official trading calendar + research data
        |
 READY_FOR_RESEARCH
```

`READY_FOR_EXECUTION` remains unavailable. Company-name association may help research grouping, but broker identity resolution uses official NSE symbol/ISIN and exact Groww NSE/CASH instrument rows. Identifier disagreement fails closed.

## Resilient NSE client

`DirectResearchClient` bootstraps an NSE browser-like HTTPS session, retries bounded 401/403/429 failures, rejects HTML/challenge pages, defensively parses changing JSON envelopes, and caches successful NSE responses. Per-source health is published as **FRESH**, **CACHED**, or **FAILED** so source failure is never represented as “no IPOs.”

The foreground research refresh interval is 30 minutes while the app is active. The uploaded v1.3.2 audit showed current NSE refreshes receiving HTTP 403 and correctly falling back to cached last-known-good data; v1.3.3 preserves that fail-safe behavior while exposing durable calls separately.

## Notifications and recovery access

The app requests normal `POST_NOTIFICATIONS` permission for its own alerts. v1.3.3 also provides an optional Android Notification Access setting for the `BrokerNotificationListenerService`.

The listener does not parse notification content into an order/position truth state. A Groww notification simply wakes the broker reconciler, which then queries Groww directly. If listener access is revoked or the OEM kills the listener, boot/app-open/WorkManager/manual triggers still provide recovery opportunities.

## Audit

Weekly verification export now includes:

- app audit JSONL;
- weekly summary;
- research-source health;
- durable calls ledger;
- broker-truth recovery status.

Groww credentials and access tokens are never exported.

## Security and execution boundary

- `android:usesCleartextTraffic="false"`
- HTTPS-only active networking
- Android Keystore-backed credential encryption
- no credentials/signing keys in the repository
- direct Groww broker reads only for recovery
- automatic real-money execution remains **LOCKED OFF**
- CI gates prevent active Android UI/recovery state from using the historical `BackendApi` polling path

## CI / build

`.github/workflows/ipo-sentinel-apk.yml` validates pull requests and main-branch builds with backend tests, architecture/security gates, Android lint, Kotlin compilation, APK assembly, SHA-256 generation, and artifact upload.

The expected artifact is `IPO-Sentinel-v1.3.3-RECOVERY-debug.apk`.
