# Standalone watch recorder

User chose: preserve a navigable normal watch interface, start/stop on watch, record without phone or SD card into internal flash, transfer to Android later over BLE. Wi-Fi is optional future transport; BLE first. Preserve factory backup and existing phone data. Tooling/dependencies stay repository-local.

## Implementation
- Build factory Brookesia source pinned by existing Waveshare checkout using local ESP-IDF5.5.5. Stage factory in ignored cache; tracked overlay adds Motion Recorder app and recording/transfer service. Preserve original launcher/apps; sensor consumers must share read-only snapshots rather than reconfigure recording IMU.
- Reserve a dedicated 6 MiB partition in the verified-erased 16–22 MiB range of confirmed32MiB flash; preserve older data above22MiB. Bound recording to measured free space and show storage/time/status. Do not silently erase saved sessions on mount failure. Record fixed binary motion packets with session metadata and recover interrupted sessions without discarding good packets. Return Home while recording continues.
- Keep existing live BLE service/data characteristic. Add explicit session inventory and packet transfer with offset/length, integrity checks and client ACK flow control; interrupted transfer retries from a verified session offset. Watch files remain until explicit user deletion; Android imports only verified complete downloads using existing CSV/metadata interfaces.
- Add Android Watch recordings page to list/download sessions and expose them in current analysis/history; no phone session required while recording on watch. No cloud, SDK/global config or package changes.

## Acceptance
- Host tests for binary framing, recovery/truncation, bounded space, transfer validation and duplicate imports. Factory build and backup verification before flash. Device boot/home/touch navigation + watch Start/Stop checked separately from compilation.
- Record with phone disconnected, navigate Home without stopping, reconnect and import to Android, verify sample timestamps/count/content and interruption/retry. State any unverified physical UI actions.

## Progress
- Existing32MiB full factory backup verified. Factory source builds with the Motion Recorder overlay and local ESP-IDF. Original resources/settings restored during initial migration. BLE initializes before display/network allocations. Runtime boot testing found internal-RAM pressure from eager Wi-Fi and AIChats initialization; these services now start on demand. Physical Start/Stop and real archive transfer validation remain pending.
- Host C++ format tests and Java analytics/import tests pass. Standalone review fixes include checking partition erasure before wear-levelling mount, draining queued samples on Stop, and discarding completed corrupt phone download parts so retries can succeed.
- Final firmware flashed with device hash verification; boot logs show Motion Recorder installed, AXP2101 battery monitoring started with Wi-Fi disabled, and normal launcher readiness. Android0.4 installed and device fixture/storage/export/navigation checks pass. Awaiting user physical offline Start/Home/Stop test before real BLE archive/resume verification.
- User confirmed the offline session saved. Real archive device checks passed: inventory, corrupt-complete rejection/retry, cancel/reconnect/resume, CRC/count-verified import (1550 samples,14.407478s), duplicate preservation and Watch library UI. Longer unplugged screen-off/battery and power-loss tests remain open.
