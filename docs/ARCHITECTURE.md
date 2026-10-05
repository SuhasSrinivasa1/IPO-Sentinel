# IPO Sentinel Architecture — v1.3.2

## Principle

IPO Sentinel v1.3.2 is one synchronized Android application, not a collection of independent screen subsystems. All production screens consume `AppStateRepository`.

## Runtime graph

```text
                         AppStateRepository
                                |
             -----------------------------------------
             |                                       |
       DirectGrowwClient                    DirectResearchClient
             |                                       |
   Android Keystore + TOTP            NSE session + source caches
   Groww HTTPS authentication         IPO discovery / listing identity
   HTTPS public-IP check              Groww NSE/CASH instrument master
             |                                       |
             ---------------- Shared AppState --------
                                |
          ------------------------------------------------
          |                 |                 |           |
      Dashboard          Research          Strategies   Settings
```

The repository hydrates persisted Groww validation, the cached research plan, per-source health, and the local strategy catalog before/while it performs current validation and research refreshes. Screens do not own independent validation or research truth.

## Groww session state

`DirectGrowwClient` owns:

- encrypted TOTP token/API key;
- encrypted TOTP secret;
- encrypted access token;
- local TOTP generation;
- fixed `https://api.groww.in` authentication;
- expected static public IP;
- user confirmation that the IP was whitelisted;
- current HTTPS-detected public egress IP;
- persisted last validation.

The visible secret inputs are always blank on screen creation. Existing credentials are represented only as a saved/not-saved state.

A Groww connection is considered ready for research connectivity when authentication, Keystore, expected-IP match, and whitelist confirmation pass. That is **not** execution readiness.

## Research source state

The direct research client maintains independent health for:

- NSE cash-market holiday calendar;
- NSE upcoming IPO issues;
- NSE current IPO issues;
- NSE forthcoming listings;
- NSE recent listings;
- Groww public NSE/CASH instrument master.

NSE requests use a bootstrapped browser-like cookie session. 401/403/429 responses trigger bounded re-bootstrap/retry. HTML/challenge responses are rejected rather than parsed as JSON.

Each successful NSE JSON response is cached with a timestamp. On a transient failure, the client may use the last successful payload and marks the source `CACHED`. If no usable cache exists, the source is `FAILED`. A failed source is never translated into “no IPOs.”

## IPO identity lifecycle

The executable identity boundary is strict:

```text
DISCOVERED
  official NSE discovery record; name association is research-only

IDENTITY_VERIFIED
  final NSE listing identity has official symbol + listing date
  (ISIN is used when available)

GROWW_SYMBOL_VERIFIED
  exact NSE/CASH Groww instrument row matches official symbol
  and, when available, official ISIN

READY_FOR_RESEARCH
  exact identity is resolved and official trading-calendar data is usable

READY_FOR_EXECUTION
  unavailable / false in v1.3.2
```

No fuzzy company-name match can authorize a broker symbol. If symbol and ISIN evidence disagree, resolution fails closed.

## Research semantics

The engine covers upcoming/listing-day research and the D1-D30 post-listing window across Mainboard and SME names. Research scores are not live trade signals. Without current market evidence, the UI uses **WATCH**, **RESEARCH**, and **WAIT LIVE CONFIRMATION**.

The application does not fabricate entry prices, stop losses, targets, confidence, win rates, or strategy performance.

## Strategy evidence

`LocalStrategyCatalog` contains 19 strategy families. Catalog existence and evidence existence are separate concepts. v1.3.2 truthfully reports zero tested families/champions until real replay/live evidence is collected.

## Execution boundary

Real-money automatic trading is locked off. `ValidationStatus.liveExecutionReady` is forced false by the direct Groww client and shared repository.

The legacy foreground polling loop was removed. `LiveNotificationService` is retained only as inert source/binary compatibility code and is not declared in the Android manifest.

A future execution module must attach beneath the same shared repository/Groww session/research/risk state. It must not create a parallel control plane.

## Legacy backend boundary

The historical Python backend and `BackendApi.kt` can remain for tests/reference. Active Android files—`MainActivity`, `AppStateRepository`, `AppAudit`, and `LiveNotificationService`—must not call the legacy backend.

CI enforces this boundary.

## Network and security

- active production networking is HTTPS only;
- Android cleartext traffic is disabled;
- no user-entered server endpoint;
- no device ID/device key provisioning;
- no secrets in Git;
- credential values are not logged;
- weekly audit export is local and excludes broker secrets.

## CI gates

The release workflow verifies version 1.3.2, secret hygiene, cleartext prohibition, shared-state presence, direct Groww/NSE endpoints, IPO lifecycle constants, locked execution readiness, absence of active `BackendApi` calls, absence of a manifest polling service, lint, Kotlin compilation, APK assembly, and SHA-256 generation.
