# Validation

- Unified Android release compiles and passes Gradle release lint with SDK 34 and JDK 17.
- Signed APK installed successfully on AYN Thor. Only com.lookingglass.alice.MainActivity is declared as a launcher; there is no separate dash launcher or service.
- User confirmed the real folder-picker flow worked on the Thor. The integrated HUD logged its creation on display 4 and a successful connection to the game's state server.
- No second HUD import prompt is implemented: its importer receives file URIs from the native launcher's app-owned base/ directory.
- The existing map zoom and weapon-selection implementation is preserved. The native game independently checks equip commands.
- Reuse of generated assets is implemented through a completion marker and archive metadata persisted in app preferences. A manual force-stop/relaunch test was not performed during active play.
- The final packaging pass also changes the update dialog to explain DualScreen updates, preventing users being directed to a standard port APK. This UI-only change was rebuilt after the initial on-device confirmation.
- APK signatures and ZIP CRCs are verified. Public packages exclude commercial archives, generated artwork, saves, personal workstation paths and signing secrets.

The source archive omits native binary libraries: build the supplied native integration against the pinned upstream source, then run tools/import_native.py as described in BUILDING.md.
