# AGENTS

## Versioning Rule

- `versionName` tracks which upstream version this repo is caught up to / based on (e.g. `0.16.1`). Only bump `versionName` when syncing to a new upstream release; do not bump it for our own changes.
- For releases, bump `versionCode` in `app/build.gradle.kts:51` (e.g. `46 -> 47`).
- Tag releases as `v{versionName}-{versionCode}` (e.g. `v0.16.1-47`), matching the `ReadYou-{versionName}-{versionCode}.apk` output in `app/build.gradle.kts:116`.

## Git Safety Rule

- Do **not** push commits to any remote unless the user explicitly instructs you to push.

## Android Install Safety Rule

When developing or testing this project, do **not** install builds to all connected devices by default.

- Prefer installing to an emulator only.
- Do **not** install to a physical device unless the user explicitly asks for it.
- Before running install commands, ensure the target device is specified (for example, via the `ANDROID_SERIAL` environment variable or `adb -s <serial> ...`) or that only the intended emulator is connected.

