# IPO Sentinel v1.3.1 source baseline

This repository is the canonical public source repository for the current IPO Sentinel project.

## Current Android build

- Version: 1.3.1
- Version code: 131
- Application ID: `com.suhas.iposentinel.installfix`
- Namespace: `com.suhas.iposentinel`
- Minimum Android SDK: 28
- Target / compile SDK: 35
- Java/Kotlin target: 17
- UI: Kotlin + Jetpack Compose
- Cleartext HTTP: disabled
- Groww broker endpoint: fixed HTTPS endpoint in the client

## Direct Groww authentication

The direct client persists the latest validation result and revalidates saved Groww credentials on app startup, so the Dashboard no longer falls back to "NEEDS VALIDATION" solely because the process restarted.

The v1.3.1 Android application does not require a custom trading-service endpoint, device ID, or device key.

The user supplies:

- Groww TOTP token / API key
- Groww TOTP secret
- Expected whitelisted static public IP
- Static-IP confirmation

`DirectGrowwClient.kt` encrypts broker credentials using an Android Keystore key, generates the rotating TOTP locally, obtains/refreshes Groww access credentials as required by the direct client, and performs validation over HTTPS.

No real TOTP token, TOTP secret, Groww access token, signing key, keystore, password, or private environment file belongs in Git.

## Direct IPO research

`DirectResearchClient.kt` fetches the official NSE IPO/current/forthcoming/recent-listing feeds and the NSE cash-market holiday calendar directly on-device. It resolves confirmed NSE symbols against Groww's public NSE/CASH instrument master and caches the research plan for 15 minutes so the UI does not repeatedly hammer public endpoints.

Dashboard and Research no longer call the disabled legacy `BackendApi` research endpoints. Strategies uses the local 19-family catalog and clearly reports that replay/champion evidence has not yet been collected on-device.

Automatic live order placement is intentionally fail-closed in v1.3.1; no research-card action is allowed to fall through to the retired remote service.

## Source layout

- `android/` — current Android application source.
- `android/app/src/main/java/com/suhas/iposentinel/DirectGrowwClient.kt` — direct Groww authentication and secure local credential handling.
- `android/app/src/main/java/com/suhas/iposentinel/MainActivity.kt` — Compose application UI and control flow.
- `android/app/src/main/java/com/suhas/iposentinel/BackendApi.kt` — retained legacy API surface; its remote request path is disabled in direct-device mode.
- `backend/` — retained Python research/trading-service source from earlier architecture and its tests.
- `docs/` — architecture/research documentation.
- `.github/workflows/ipo-sentinel-apk.yml` — backend validation, Android lint/build, direct-auth architecture checks, APK artifact and SHA-256 generation.

## Public repository hygiene

The repository intentionally ignores APK/AAB outputs, local Gradle state, Python virtual environments, `.env` files, Android keystores, JKS files, and `secrets.properties`.

The GitHub Actions workflow also checks tracked files for common private signing-key material before building.

## Build

The current workflow installs Java 17, Android SDK 35 and Gradle 8.10.2, runs Android lint, builds the debug APK, verifies that legacy endpoint/device-key configuration is absent from the Android source, and uploads:

- `IPO-Sentinel-v1.3.1-DIRECT-RESEARCH-debug.apk`
- `SHA256SUMS.txt`

The Python backend test suite is retained in CI because the backend source remains part of this repository, even though the v1.3.0 Android broker-authentication path is direct-to-Groww.
