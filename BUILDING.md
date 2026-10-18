# Build the unified APK

Use JDK 17, Android SDK 34, Android build-tools 34.0.0 and Gradle 8.7. AGP 8.6.0 and Kotlin 1.9.20 are pinned. The app is arm64-only.

## Native engine

Clone https://github.com/Player124413/Looking-Glass-android-edition and check out commit `e8f2ebe805e3d8d463c38b953ed0b883f8e3cc89`. Apply `game/dualscreen.patch` and copy `game/state_export.rs` to its `src/` directory. Build using the upstream Android packager with Rust, NDK 27.2.12479018 and Python 3.11+:

```
python tools/package_android.py --require-apk
```

The source patch adds the state/command bridge. This unified project supplies the replacement Android Activity and HUD, so the intermediate engine APK is only a native-library build input. Its development signature is not used for the unified release.

Copy its native libraries into this project:

```
python tools/import_native.py /path/to/LookingGlass-v0.32.0.88-android.apk
```

No commercial game data is needed to build. On Windows, prefer a short build path without spaces and retain JDK 17. For public Rust builds, remap personal workstation paths with rustc `--remap-path-prefix` and inspect the resulting libraries.

## Android application

Set JAVA_HOME and ANDROID_HOME, then run from this folder:

```
gradle :app:assembleRelease --console=plain
```

Sign `app/build/outputs/apk/release/app-release-unsigned.apk` using your own private release key with Android apksigner. Retain the key for updates. Name the output `LookingGlass-DualScreen-v0.32.0.88-arm64.apk`. Do not include keys, passwords, build caches or imported game files in source control.

## Integration

MainActivity preserves the native game's launcher and its folder/archive importer. DualScreenController owns a Presentation on an available presentation display, follows Activity visibility, and reads the launcher's app-owned `base/` archives. AssetImport stages the generated private HUD pack and replaces it only after successful completion. Archive name/size/mtime metadata prevents unnecessary rebaking on subsequent launches. A failed bake can be retried on the bottom screen.

The existing loopback NDJSON state/command bridge remains inside the single application. Equip commands are checked by the native game. The same Activity owns both screens; there is no companion Activity, foreground service, second launcher icon or second permission/import flow.
