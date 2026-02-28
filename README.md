<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" alt="Blackbox app icon" width="112" />
</p>

Blackbox is a privacy-focused open-source Android app for local location logging and secure end-to-end encrypted location sharing.

# Blackbox

Private Android location app with:
- Secure end-to-end encrypted (E2EE) location sharing.
- Local encrypted location logging.
- Local map-based history and viewing.

No account is required. 

## Install
Releases (APK) are available on this GitHub page.

## Technical Info
- `app/`: Android client (UI, location engine, encrypted local logging, map/history views, sharing logic).
- `relay/`: Optional Cloudflare Worker relay for message delivery between peers.
- Relay design: relay handles transport/state only; shared location payloads are end-to-end encrypted and not readable by the relay.
- Local data: location history is encrypted on-device and remains local unless you explicitly export/share.

## Security
Location sharing is E2EE and local history is encrypted on-device.
For vulnerability reporting, see [SECURITY.md](SECURITY.md).

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md).

## License
MIT. See [LICENSE](LICENSE).
