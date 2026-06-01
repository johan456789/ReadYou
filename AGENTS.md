# AGENTS

## Android Install Safety Rule

When developing or testing this project, do **not** install builds to all connected devices by default.

- Prefer installing to an emulator only.
- Do **not** install to a physical device unless the user explicitly asks for it.
- Before running install commands, ensure the target device is specified (for example, via `adb -s <serial> ...`) or that only the intended emulator is connected.

