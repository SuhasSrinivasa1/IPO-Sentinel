# IPO Sentinel Architecture — v1.3.3

## Principle

IPO Sentinel v1.3.3 uses one shared Android state model. Research discovery, durable recommendation calls, broker reconciliation, recovery triggers, and UI state converge through `AppStateRepository`. Automatic execution remains disabled.

## Runtime graph

```text
                   AppStateRepository
                         |
        -------------------------------------
        |                |                  |
 DirectGrowwClient DirectResearchClient CallLedgerStore
        |                |                  |
 Keystore/TOTP      NSE/source cache    durable calls
        |                |                  |
        ----------- BrokerTruthClient -------
                         |
             Groww order list + positions
                         |
                RecoveryCoordinator
                         |
     ------------------------------------------------
     |          |          |          |             |
 listener     boot      app open   WorkManager    manual
 reconnect                           periodic     reconcile
                         |
      Home • Calls • Research • Strategy • Settings
```

## Durable call semantics

A `RecommendationCall` persists independently of the activity, Compose state, notification listener, or process lifetime. It includes first recommendation, update, research-source, broker-reconciliation, and close timestamps.

The call ledger is written to local application storage. Research refreshes update qualifying calls but do not close them when NSE is degraded/cached. A normal healthy research refresh may retire calls that genuinely leave the active research universe.

Broker reconciliation may close a call when the app previously observed a non-zero matching broker position and a later authoritative Groww positions response reports it flat.

## Recovery contract

The system assumes Android/OEM process death can occur at any point.

Recovery triggers are deliberately redundant:

1. `BrokerNotificationListenerService.onListenerConnected`;
2. Groww notification posted;
3. `BOOT_COMPLETED`;
4. `MY_PACKAGE_REPLACED`;
5. application startup;
6. network-constrained periodic WorkManager work;
7. user-triggered reconciliation.

All triggers invoke the same serialized `RecoveryCoordinator`. A short debounce prevents notification bursts from causing excessive broker reads.

The notification listener is a **wake source only**. Notification text is not used as authoritative order/fill/position state. Broker truth is fetched directly using Groww's read-only order list and positions endpoints.

## Groww session and broker truth

`DirectGrowwClient` owns encrypted credentials, local TOTP generation, encrypted access tokens, public-IP validation, and connection readiness.

`BrokerTruthClient` uses direct HTTPS and reads:

- CASH order list;
- CASH positions.

A recent encrypted access token is reused; 401/403 broker responses force one re-authentication and retry. Broker snapshots are themselves persisted so UI/audit can display the last reconciliation status even after process restart.

No order placement, modification, or cancellation is performed by the recovery path.

## Research identity boundary

```text
DISCOVERED
  -> IDENTITY_VERIFIED
  -> GROWW_SYMBOL_VERIFIED
  -> READY_FOR_RESEARCH
```

Exact NSE/CASH broker identity is required before a research candidate enters the call ledger. Fuzzy company-name matching cannot authorize a broker symbol.

Research still covers listing-day/upcoming candidates and D1-D30 Mainboard/SME names. Without live market evidence the app uses WAIT LIVE CONFIRMATION semantics rather than inventing entries, stops, targets, or confidence.

## NSE source state

NSE source health is independent from call state. The client caches last-known-good source payloads and marks each source FRESH, CACHED, or FAILED. Transient 403/429/HTML challenge behavior therefore degrades research freshness without erasing durable recommendations.

## Android lifecycle integration

The manifest declares:

- INTERNET;
- POST_NOTIFICATIONS;
- RECEIVE_BOOT_COMPLETED;
- `BrokerNotificationListenerService` bound through `BIND_NOTIFICATION_LISTENER_SERVICE`;
- `RecoveryBootReceiver`;
- FileProvider.

WorkManager schedules a network-constrained 30-minute reconciliation. This is not a guarantee of exact wall-clock execution under OEM power management; it is a persistence/retry mechanism combined with listener, boot, app-open, and manual recovery triggers.

## Execution boundary

`ValidationStatus.liveExecutionReady` and `AppState.executionReady` remain false. v1.3.3 is a recommendation + reconciliation release, not an auto-trading release.

A future execution implementation must add safe placement, idempotent client references, order-state reconciliation, fill handling, position ownership, risk controls, and restart-safe pending actions beneath this same shared architecture.

## Audit and CI

Weekly audit export includes call-ledger and broker-truth status in addition to application and source-health logs, while excluding credentials/tokens.

CI validates version 1.3.3, direct HTTPS architecture, notification/boot recovery declarations, WorkManager recovery, durable timestamps, Groww broker-read endpoints, locked execution, no active legacy BackendApi polling, lint, Kotlin compilation, APK assembly, and SHA-256 generation.
