# IPO Sentinel v1.3.0 source baseline

This repository is the canonical public source repository for the current IPO Sentinel project.

## Current Android build

- Version: 1.3.0
- Version code: 130
- Application ID: `com.suhas.iposentinel.installfix`
- Namespace: `com.suhas.iposentinel`
- Minimum Android SDK: 28
- Target / compile SDK: 35
- Java/Kotlin target: 17
- UI: Kotlin + Jetpack Compose
- Cleartext HTTP: disabled
- Groww broker endpoint: fixed HTTPS endpoint in the client

## Direct Groww authentication

The v1.3.0 Android application does not require a custom trading-service endpoint, device ID, or device key.

The user supplies:

- Groww TOTP token / API key
- Groww TOTP secret
- Expected whitelisted static public IP
- Static-IP confirmation

`DirectGrowwClient.kt` encrypts broker credentials using an Android Keystore key, generates the rotating TOTP locally, obtains/refreshes Groww access credentials as required by the direct client, and performs validation over HTTPS.

No real TOTP token, TOTP secret, Groww access token, signing key, keystore, password, or private environment file belongs in Git.

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

- `IPO-Sentinel-v1.3.0-DIRECT-debug.apk`
- `SHA256SUMS.txt`

The Python backend test suite is retained in CI because the backend source remains part of this repository, even though the v1.3.0 Android broker-authentication path is direct-to-Groww.
