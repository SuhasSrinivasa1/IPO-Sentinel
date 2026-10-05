# IPO Sentinel

IPO Sentinel is an Android-first, read-only IPO research application for NSE listings. The production Android path communicates directly with Groww and official NSE sources over HTTPS. The historical Python backend remains in the repository for reference/tests, but the Android UI does not depend on it.

## Current release target

- Version: **1.3.2**
- Version code: **132**
- Android application ID: `com.suhas.iposentinel.installfix`
- Artifact: `IPO-Sentinel-v1.3.2-DIRECT-debug.apk`
- Compile / target SDK: 35
- Java: 17

## v1.3.2 architecture

The Android application has one shared application-level source of truth:

```text
                    AppStateRepository
                           |
              ---------------------------
              |                         |
       DirectGrowwClient       DirectResearchClient
              |                         |
       Groww HTTPS auth          Official NSE sources
       Static egress IP          + resilient source cache
       Android Keystore          + Groww instrument master
              |                         |
              -------- Shared State -----
                           |
        -----------------------------------------
        |              |              |         |
    Dashboard       Research       Strategies  Settings
```

`Dashboard`, `Research`, `Strategies`, and `Settings` all read from the same `StateFlow<AppState>`. Validation and research state are hydrated from local persistence before network refreshes update the repository.

## Groww authentication and secrets

Groww authentication is direct from the Android app using the Groww TOTP token/API key plus a locally generated TOTP from the saved secret. The token, TOTP secret, and generated access token are encrypted using an Android Keystore-backed AES/GCM key.

The app does **not** accept a custom trading-service URL, device ID, device key, backend endpoint, or cleartext HTTP endpoint. Stored secrets are never repopulated into visible Settings fields.

The static-IP gate compares the configured expected public IP with the current HTTPS-detected egress IP. A checkbox alone never makes the connection ready.

## IPO discovery and identity

The research pipeline deliberately separates names, NSE identities, and Groww instruments:

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

`READY_FOR_EXECUTION` is not enabled in v1.3.2. Company-name association may help non-executable research grouping, but actual broker identity resolution uses official NSE symbol/ISIN and exact Groww NSE/CASH instrument rows. Identifier disagreement fails closed.

## Resilient NSE client

`DirectResearchClient`:

- bootstraps an NSE browser-like HTTPS session and cookie jar;
- sends realistic User-Agent, Accept, Referer, language, and connection headers;
- follows redirects;
- retries 401/403/429 with bounded backoff and re-bootstrap;
- rejects HTML, CAPTCHA, access-denied, and non-JSON responses;
- defensively walks changing JSON envelopes;
- caches each successful NSE source locally for up to seven days;
- caches the relevant Groww instrument rows;
- preserves the last known-good IPO universe across transient failures;
- distinguishes **zero candidates from an available source** from **source unavailable**;
- publishes per-source `FRESH`, `CACHED`, or `FAILED` health.

The plan cache prevents constant NSE polling; the foreground UI refresh interval is 30 minutes while the application is active.

## Research and strategies

Research covers next-listing candidates and D1-D30 post-listing monitoring for Mainboard and SME names. Rankings are research-level only. Without live price/volume/depth evidence, the app reports **WATCH / RESEARCH / WAIT LIVE CONFIRMATION** and does not manufacture entries, stops, targets, confidence, win rates, or performance.

The local strategy catalog contains 19 strategy families. Until replay/live evidence exists, the truthful state is:

- Total: 19
- Tested: 0
- Champions: 0

## Live execution

Automatic real-money order placement is intentionally **locked off** in v1.3.2. The prior foreground service no longer polls `BackendApi`, and the manifest contains no polling service. Direct execution must not be enabled until order placement, reconciliation, fill tracking, position ownership isolation, order-state tracking, and risk controls exist under the same shared architecture.

## Notifications and audit

The app requests the normal Android `POST_NOTIFICATIONS` permission only. It does not request notification-listener access to read other apps' notifications. Settings includes **Send Test Notification** and local weekly audit export. Audit exports exclude Groww secrets and access tokens.

## Security

- `android:usesCleartextTraffic="false"`
- HTTPS-only active networking
- Android Keystore-backed credential encryption
- no credentials or signing keys in the repository
- GitHub Actions secret-hygiene gate
- CI architecture gates prevent active Android UI/state from calling the legacy backend path

## CI / build

`.github/workflows/ipo-sentinel-apk.yml` runs:

1. public-repository secret hygiene checks;
2. Python backend compile/tests/dependency check;
3. Java 17 + Android SDK 35 setup;
4. Android architecture/security gates;
5. Android lint;
6. Kotlin compilation;
7. debug APK assembly;
8. SHA-256 generation;
9. artifact upload containing the actual APK and `SHA256SUMS.txt`.

The expected artifact file is `IPO-Sentinel-v1.3.2-DIRECT-debug.apk`.

## Historical backend

`backend/` and `BackendApi.kt` remain for historical/reference/test purposes. The production Android screens, shared state repository, audit export, and notification path do not call that legacy remote API.
