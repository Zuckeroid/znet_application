# znet Android VPN (Xray)

MVP Android client for znet with:
- Xray core runner (native binary from assets)
- VPN UI inspired by the provided home screen
- Split tunneling (exclude selected apps from VPN)
- Auto-off VPN when selected app is foreground
- Access bundle from billing `Appbridge`
- Telemetry placeholder for future `Appbridge` transport

## Stack
- Kotlin
- Jetpack Compose
- Android `VpnService`
- Xray core process runner
- OkHttp + Kotlinx Serialization

## Project structure
- `app/src/main/java/com/znet/app/vpn` - VPN service, Xray runner, connection status bus
- `app/src/main/java/com/znet/app/data` - preferences, models, app bridge client, config builders
- `app/src/main/java/com/znet/app/ui` - Compose UI (Home/Servers/Settings)
- `app/src/main/java/com/znet/app/workers` - telemetry placeholder for future v2

## Required setup
1. Put your xray binary at:
   - `app/src/main/assets/xray/xray`
2. Use Android JDK 17+ for build.
3. In app, user enters only token (no URL field in UI).

## Build configuration
Build values can be provided through environment variables, `gradle.properties`, or local `keystore.properties`.

- `ZNET_AUTH_API_URL` / `ZNET_AUTH_API_URLS` - shared bootstrap API domains.
- `ZNET_DEBUG_AUTH_API_URL` / `ZNET_DEBUG_AUTH_API_URLS` - debug override.
- `ZNET_RELEASE_AUTH_API_URL` / `ZNET_RELEASE_AUTH_API_URLS` - release override.
- `ZNET_VERSION_CODE` / `ZNET_VERSION_NAME` - app version.
- `ZNET_DEBUG_APPLICATION_ID_SUFFIX` - optional debug suffix, for example `.dev`.

Debug keeps the production package id by default, so a local install does not reset the current test device session.

## Release signing
Release signing is intentionally local-only. Copy `keystore.properties.example` to `keystore.properties` and fill:

- `ZNET_RELEASE_STORE_FILE`
- `ZNET_RELEASE_STORE_PASSWORD`
- `ZNET_RELEASE_KEY_ALIAS`
- `ZNET_RELEASE_KEY_PASSWORD`

`keystore.properties` and keystore files are ignored by git.

## Billing API expected
- `POST /api/guest/appbridge/token_login`

The app now treats billing as the only external API surface. The orchestrator remains an internal backend layer behind billing.

## Important note on core integration
This MVP starts Xray process and exposes TUN file descriptor through `XRAY_TUN_FD` env var.
Your Xray mobile wrapper/config should consume this descriptor or use your existing Android Xray integration module.

## Build
`./gradlew assembleDebug`

`./gradlew assembleRelease`

If Java 8 is installed globally, point Gradle to JDK 17 in Android Studio settings or `JAVA_HOME`.
