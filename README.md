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
3. Set constant auth API URL before build:
   - Windows PowerShell (current session): `$env:ZNET_AUTH_API_URL="https://your-api.example.com"`
   - or in `gradle.properties`: `ZNET_AUTH_API_URL=https://your-api.example.com`
4. In app, user enters only token (no URL field in UI).

## Billing API expected
- `POST /api/guest/appbridge/token_login`

The app now treats billing as the only external API surface. The orchestrator remains an internal backend layer behind billing.

## Important note on core integration
This MVP starts Xray process and exposes TUN file descriptor through `XRAY_TUN_FD` env var.
Your Xray mobile wrapper/config should consume this descriptor or use your existing Android Xray integration module.

## Build
`./gradlew assembleDebug`

If Java 8 is installed globally, point Gradle to JDK 17 in Android Studio settings or `JAVA_HOME`.
