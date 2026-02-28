# Changelog

All notable changes to this project will be documented in this file.

## v1.1.2 - 2026-02-28
- Fixed `Retrieve Locations` timer UI to stay at the full interval when retrieve is inactive, instead of counting down while muted.
- Fixed `Sending Location` timer UI to reset to the full interval when sending is inactive, instead of remaining at `0s`.

## v1.1.1 - 2026-02-28
- Renamed sharing backup actions from `Export/Import Identity` to `Export/Import Contacts`.
- Updated sharing backup file naming to `blackbox-contacts-bundle.json`.
- Expanded sharing backup payloads to include both sharing identity and the full contacts list.
- Added contacts import sanitization and contact-limit enforcement during restore.
- Kept compatibility with older identity-only sharing bundles during import.
- Required a selected archive/database folder before enabling location logging.
- Added a simple explanatory popup before opening folder selection when logging is enabled without a folder.
- Auto-disabled location logging when archive folder selection is cleared.

## v1.1.0 - 2026-02-28
- Updated app metadata.
- Updated app icon.
- Opened the exact failing settings editor when sharing validation blocks enabling sharing.
- Adjusted debug-page entry hold behavior and back navigation.
- Kept the location engine active while the app is running, independent of sharing/logging toggles.
- Bootstrapped initial fix acquisition with a temporary high-demand location consumer.
- Requested notification permission when missing.
- Auto-enforced foreground keepalive based on sharing/logging state across app lifecycle.
- Refined popup dialog layout width and centered title cards.

## v1.0.0 - 2026-02-28
- Initial release.
