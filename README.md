<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" alt="Blackbox app icon" width="112" />
</p>
<h1 align="center">Blackbox</h1>

Privacy-first Android location app with local encrypted storage, background location engine controls, and optional end-to-end encrypted relay sharing.

## Features
- Location engine controls and live location diagnostics.
- Local encrypted persistence and archive/export tools.
- End-to-end encrypted location sharing (key-based, no accounts).
- Cloudflare Worker relay implementation under `relay/`.

## Quick Start
1. Install Android Studio (latest stable) and JDK 17.
2. Clone this repository.
3. Create `local.properties` from `local.properties.example` and set `sdk.dir`.
4. Build:
   - `./gradlew :app:assembleDebug`
5. Run the app on device/emulator from Android Studio.

## Project Structure
- `app/` Android application module.
- `relay/` Cloudflare Worker relay server.
- `assets/` static assets used by the app.

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md).

## Security
See [SECURITY.md](SECURITY.md).

## License
MIT. See [LICENSE](LICENSE).
