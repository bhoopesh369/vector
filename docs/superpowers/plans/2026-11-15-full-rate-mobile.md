# Full-rate mobile recording

User requests the same acquired motion samples in phone recordings as watch recordings. Authorized implementation: batched BLE notifications, bounded queues and measured CSV verification; keep reduced UI preview and existing saved-session download. No synthetic/interpolated samples. Main is merged; create codex/full-rate-mobile and preserve staged IDE file. No global tooling/config changes.

## Protocol and tasks
1. Add new UUID0005 notify stream; old UUID0002 stays reduced preview for older apps. Batch header8 bytes:0xA3,version1,count,flags(bit0 queue overrun),uint32LE cumulative queue dropped samples; followed by count unchanged26-byte raw v1 packets. Choose count from negotiated MTU, max18. Tests cover framing, bounds, preview rejection and loss flags.
2. Firmware queues every acquired sample for subscribed full stream in PSRAM. Dedicated sender batches, retries backpressure without blocking acquisition, bounded queue reports loss explicitly. Clear queue on subscription/disconnection; preserve acquisition sequences and timestamps. Negotiate517MTU and fast connection parameters. Saved-file transfer takes exclusive stream time; prevent file transfer while phone recording as before.
3. Android prefers UUID0005, decodes all frames in worker, writes every frame, UI chart remains reduced. Preserve old v1/v2 support and label full-stream provenance/timing; distinguish reported transport queue loss from acquisition gaps. Stop interrupted recording on confirmed overrun rather than silently claiming complete data.
4. Host tests, builds, final code review, install on existing devices if available, verify real phone CSV rate/count/gaps through screen-off/navigation. Never claim uniform/lossless1000Hz: underlying FIFO acquisition gaps remain.

Ruling: cap batches at18 samples so476-byte values fit in two251-byte link-layer data packets including ATT/L2CAP headers. Request DLE251 and2M PHY with normal fallback; actual phone measurements decide throughput.

## Verification ledger
- New Java/C++ batch tests first failed on missing implementation, then passed. MTU efficiency test changed from19 to18 and failed before the matching implementation change.
- Legacy Android analytics/packet/import tests and full Python suite pass. New framing/loss/timestamp tests pass. Both firmware and Android0.6 builds pass.
- Fresh reviewer found no blocking P1/P2 issue in full-rate path. Runtime MTU override is valid up to527 in installed NimBLE; real negotiation confirmed517 despite cached sdkconfig default247. New clean builds use517 defaults.
- Installed both updates preserving recordings. Real phone full-rate test passed:36659 samples/31.213151s=1174.44086Hz, missing0,maxGap20764us,35248 new samples during30-second screen-off interval. Full-rate metadata and chart-decimation independence verified.
- FullRateChecks service-level path works locked. Separate default visible-UI check reports phone locked; no visible UI verification claimed. Underlying FIFO gaps and long-duration battery behavior remain limitations.
- Independently exported and SHA-256-verified the actual phone CSV under ignored recordings/other-fullrate-2026-11-14T22-21-07.434841Z.csv. Host parsed36659 rows and recomputed1174.44086Hz; every adjacent sequence increments by1. Metadata source=bluetooth_fullrate. Temporary device harness removed; product app reopened. No user recordings deleted, no global config/package changes, staged IDE file preserved.
