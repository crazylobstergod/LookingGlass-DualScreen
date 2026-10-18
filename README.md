# Looking Glass DualScreen

One Android app for American McGee's Alice on AYN Thor: gameplay on the main screen, textured minimap, meters, inventory and touch weapon selection on the bottom screen.

[Download the latest APK](https://github.com/crazylobstergod/LookingGlass-DualScreen/releases/latest)

## Install and play

1. Install **LookingGlass-DualScreen-v0.32.0.88-arm64.apk**. This is the only APK needed; do not install Alice Dash separately.
2. Open **Looking Glass DualScreen**. Use the game's folder picker to select your own Alice/Looking Glass data folder (the folder containing the original PK3 archives, or its parent).
3. The normal game launcher imports the archives once. The bottom screen automatically prepares its art and textured maps from those same imported files. Progress appears on the bottom display; the game can continue on the main screen.
4. Launch or continue the game normally. Subsequent app launches reuse both the imported game files and prepared HUD artwork. The map slider adjusts zoom; tapping an owned weapon equips it.

The launcher also retains its original archive/file-selection support. No separate PC extractor or second app launch is needed. First-time preparation takes longer than later starts. If preparation fails, the bottom screen offers retry; select the complete original archive set if files are missing. Imported data and saves are stored locally.

No commercial game data is included. Supply your own game files. This is an unofficial mod of the Looking Glass Android port, not an official release from the game's publisher.

## Updating

Install an update over this app using the same signing certificate. Back up saves before changing distributions. If Android reports an incompatible signature, do not uninstall without backing up: uninstalling erases app data. The old separate Alice Dash app is no longer required. Use releases of this DualScreen project; installing the standard upstream port removes this HUD integration.

## Credits and source

Based on Player124413/Looking-Glass-android-edition and the original LookingGlass project by skulitom. Upstream credits remain in the app. Dual-screen integration and tooling were developed with OpenAI Codex assistance. Artwork is generated locally from the user's game data, not AI-generated or bundled.

See BUILDING.md for reproducible source inputs and THIRD_PARTY_NOTICES.md for licenses. The single APK contains both the native engine and HUD; the source ZIP contains source and patches, not the commercial game or a private signing key.
