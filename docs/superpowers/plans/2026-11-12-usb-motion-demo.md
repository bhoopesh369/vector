# USB Motion Demo Implementation Plan

> Execute inline in this project. The user authorized setup and firmware work with “proceed.”

**Goal:** Obtain timestamped six-axis readings from the connected watch on the Mac.

**Architecture:** Arduino firmware reads QMI8658 and streams CSV over USB. A Python capture utility saves valid records and reports acquisition quality. Local tool and vendor caches remain untracked.

**Tech Stack:** Arduino ESP32, vendor SensorLib/display drivers, Python and pyserial.

**Spec:** ../specs/2026-11-12-usb-motion-demo-design.md

## Global constraints
- Use this exact board's pins and power initialization.
- Back up flash and establish restoration before upload.
- No SD, BLE, Android, or sport-metric implementation in this initial milestone.
- Keep backups and personal recordings out of Git.

## Tasks
- [x] Inspect official source and install pinned, project-local tooling.
- [x] Identify chip/flash, back up existing flash, verify its length/checksum, document restore.
- [x] Test capture handling for valid readings, malformed/non-finite data, gaps and timestamp resets; implement capture utility.
- [x] Prepare firmware from official hardware initialization with explicit units and a bounded sample rate; compile.
- [x] Upload after backup verification; capture actual readings; report achieved checks and remaining motion validation.

## Review focus
Serial opening must not unintentionally toggle reset; bad or diagnostic lines must not corrupt CSV; disconnect/no-data must fail clearly; gaps/reset must be visible; sampling must not block indefinitely when USB is absent.

## Execution record
- New empty repository; no existing files or commits to preserve. Work takes place in the user-selected workspace.
- Tooling: Arduino CLI 1.6.0, ESP32 core 3.3.11; original esptool 5.1.0 legacy stub stalled during flash reads. Espressif issue #1155 suggested stub v2. Upgraded to esptool 5.3.0 with stub v2; ROM, legacy slow probe and v2 probe returned identical 64 KiB contents. Full backup runs with v2.
- Firmware configured for nominal 112.1 Hz, ±16 g and ±1024 deg/s. Ruling: initial bench demo uses vendor-supported settings; faster sports acquisition/FIFO and gyro range changes require separate validation.
- Build passed; eight host tests passed. Fresh read-only review confirmed board pins, units, USB behavior, upload offsets and backup gate. Its filename-overwrite finding was fixed with exclusive CSV/JSON creation and regression tests.

- Live completion: full 32 MiB factory backup saved with SHA-256 manifest; first 64 KiB matched the earlier probe. Upload completed with esptool hash verification for all four images. Actual restore has not been exercised.
- Two live USB captures received 1,023 and 467 valid six-axis samples at ~111.3 Hz, with no sequence gaps or clipping. Both opens restarted acquisition despite DTR/RTS initialization: 13 startup diagnostic lines were rejected, after which valid CSV was received. The capture is finite and successful, but reconnects must be treated as new sessions. Human tilt/display validation remains pending.
