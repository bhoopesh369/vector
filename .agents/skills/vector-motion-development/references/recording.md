# Recording architecture and measurements

Snapshot from 15 November 2026 for Android v0.7 at `3989616`. Recheck installed versions and attached hardware before device work.

## Product and source map

Waveshare ESP32-S3-Touch-AMOLED-2.06 with QMI8658C IMU. Factory/Brookesia launcher includes **Motion Recorder**, separate from the audio **Recorder**. Returning Home must leave motion capture running. Internal flash works without a phone or microSD. BLE live streaming and archive downloads are implemented; Wi-Fi file transfer and microSD motion storage are not.

| Concern | Source |
| --- | --- |
| Watch UI | `firmware/watch/components/MotionRecorder/MotionRecorder.cpp` |
| Acquisition/storage/status | Same component's `motion_service.cpp` |
| FIFO/scaling/timestamps | `include/qmi_fifo.hpp`, `include/high_rate.hpp` |
| BLE/batch framing/archive control | `motion_ble.cpp`, `include/stream_batch.hpp` |
| Watch file format | `include/record_format.hpp` |
| Phone background capture | `android/src/com/vector/motion/RecorderService.java` |
| Batch/sample decoding | Same package's `MotionBatch.java`, `MotionPacket.java` |
| Storage/import/download | `SessionStore.java`, `WatchRecording.java`, `WatchSync.java` |
| Analytics/charts | `MotionAnalytics.java`, `MotionChartView.java` |
| Navigation/screens | `MainActivity`, `HomeScreen`, `RecordScreen`, `HistoryScreen`, `AnalysisScreen`, `WatchScreen` |

Watch build overlays tracked `firmware/watch` onto factory sources staged in `.cache/watch-project`. Edit tracked sources or `scripts/prepare-watch.py`, not generated/vendor copies.

## Recording paths

1. **Standalone watch:** Start/Stop & save on watch; flash `.vmo` plus `.info`; phone downloads and verifies identity/size/CRC before import. Preserve duplicate files, cancellation/resume and existing raw data. Phone recording and archive download are mutually exclusive; transfers pause live streaming.
2. **Phone full-rate (introduced in v0.6):** Record → Connect watch → Start recording. UUID0005 saves every received acquired sample; source `bluetooth_fullrate`, filename `-fullrate`. The service writes a CSV in phone storage. Keep the standalone watch recorder idle when only a phone recording is desired. The service owns capture independently of the activity, with a foreground notification and wake lock. Budget roughly 350–400 MB of phone storage per hour at about 1,175 Hz, plus export space.
3. **Legacy preview:** UUID0002 remains compatible. Current version2 preview selects a latest sample per FIFO batch, at most100Hz notifications and often much lower. It has its own sequence counter. Never label it a complete acquired stream or accept preview frames inside raw archives. Older pre-FIFO version1 firmware remains readable.

`docs/ANDROID.md`, `docs/WATCH.md`, `docs/SPORTS-APP.md` retain older version sections. Statements such as112Hz, no FIFO, no watch storage or phone-preview-only describe historical implementations. Resolve applicability from current version/source.

## CSV clocks and long sessions

- BLE frames and raw watch archives still carry boot-relative microseconds. v0.7 phone CSVs store Unix microseconds in the existing `t_us` column. The phone anchors the last sample in the first saved packet to the BLE callback's wall clock and uses watch intervals throughout that session. BLE latency limits absolute accuracy. Sample ordering and timing gaps remain intact even if the phone clock changes later.
- v0.7 imports use `startedUnix × 1,000,000 + (sampleUs − bootStartUs)` for watch archives with a valid session clock. The archive anchor has second resolution. Without a valid clock, imported samples stay boot-relative and the history date uses the import time. Existing CSVs are preserved.
- Export session metadata with `timestampUnit`, `timeZone` and `clockSource` when available. Session dates use `Asia/Kolkata`. New drill markers keep the latest sample's timestamp in the same time base as the CSV and also record UTC wall time.
- Whole-session summaries stream CSV rows with bounded memory. Above 250,000 rows, the app offers whole-session statistics and CSV/ZIP export. Smaller sessions retain interactive charts and range statistics.
- Android v0.8 uses the control characteristic to dim the watch display and restore its brightness. The capability is announced by the optional `display_dimmed` field in the `TIME` reply. Older watch firmware retains phone recording support. The phone power button controls the phone screen.

## Sensor range and timing

- Current gyro range **±1024dps per axis**, accelerometer **±16g**. Combined gyro magnitude can exceed1024 by combining axes.
- FIFO conversion: acceleration raw/2048, gyro raw/32. Separate wire scaling: acceleration ×1000 and gyro ×16 in signed int16, reversed by Android. Do not confuse sensor and packet scales.
- Firmware flags near clipping at any axis magnitude ≥15.8g or ≥1010dps. This warns; it does not clamp readings at1010. Packet int16 saturation is separate; the phone adds no gyro cap.
- Installed SensorLib exposes only through1024dps. The QMI8658C Rev 0.9 table lists ±2048 dps for code 111. Rev A lists that code as unavailable and ends at ±1024 dps. The attached chip was read directly as WHO_AM_I=0x05, REVISION_ID=0x7C, sensor firmware0x01031E, matching Rev A. An explicitly requested temporary code111 experiment accepted the setting and produced raw sensitivity changes consistent with the older2048 scale, but accuracy/full-scale operation remains unvalidated. See the gyro experiment results in `docs/WATCH.md`. Do not describe undocumented behavior as manufacturer-supported operation.
- A supported1024→2048 increase changes raw gyro resolution from1/32 to1/16dps, without inherently changing sample rate, packet size or BLE bandwidth. Update config, FIFO decode, clipping, archive range header, import validation, metadata, analytics/UI assumptions and tests together. Android currently rejects unsupported archive ranges. Already clipped peaks are unrecoverable. Production firmware remains at1024dps; temporary diagnostic firmware was removed after testing.
- Matched ODR code2, nominal1793.6Hz, LPF mode3;128-frame FIFO,64-frame watermark,400kHz I2C. Nominal ODR is not saved throughput.
- FIFO read mode discards arriving sensor samples. Timestamps are nominally reconstructed inside batches and anchored to host time; inter-batch pauses remain. Sequence numbers cover software-acquired frames, not all possible hardware omissions. Preserve timing provenance and separate overflow/read-error/queue-drop counters.
- Saved rate: `(N-1)/(last_us-first_us)*1e6`. Average >1000Hz does not mean uniform continuous1kHz. `scripts/resample_1000.py` makes a labelled filtered derivative only from eligible contiguous faster input; it refuses gaps, does not manufacture missing motion, and never changes raw recordings.

Manufacturer/board references for rechecking:
- https://docs.waveshare.com/ESP32-S3-Touch-AMOLED-2.06
- https://www.qstcorp.com/en_imu_prod/QMI8658
- https://files.waveshare.com/upload/5/5f/QMI8658C.pdf
- https://www.qstcorp.com/upload/pdf/202210/13-52-27%20QMI8658C%20Datasheet%20Rev%20A%20%281%29.pdf

## BLE and storage contracts

UUID base `7b8e000N-6f27-4b8e-a9a1-3cd405ee1920`: N=1 service,2 preview,3 control,4 archive chunks,5 full-rate. No pairing code/bond configured. Android's turn-on-Bluetooth prompt concerns the phone radio; it does not mean the watch lacks BLE.

Raw26-byte little-endian sample: version@0=1, clipping@1, uint32 sequence@2, uint64 boot-relative microseconds@6, accel int16 XYZ@14 (divide1000), gyro int16 XYZ@20 (divide16). CSV remains `seq,t_us,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,clipped`.

Full batch:8-byte header `{0xA3,1,count,flags,uint32LE cumulative_queue_drops}` then1–18 unchanged raw samples. Flag bit0=queue loss. Validate count/length/flags and samples, reject preview/reversed/duplicate data, support uint32 wrap.

4096-sample PSRAM queue, dedicated sender with backpressure retries. Clear queue/epochs on subscription/disconnection and archive transfer transitions to prevent stale data. Explicit queue loss or confirmed sequence gaps visibly interrupt active full-rate phone recording. NimBLE notify owns its mbuf on success AND failure; retries need fresh buffers.

Request MTU517, DLE251,7.5–15ms connection intervals,2M PHY with fallback.18 samples yield476-byte values fitting two251-byte link packets including ATT/L2CAP overhead. Honor negotiated MTU; one-frame batch needs37. Cached sdkconfig may still show247 while runtime negotiates517: inspect actual negotiation.

Watch file:64-byte `VECTORM1` v1 header then26-byte packets. Header float nominalHz@48, uint16 accel range@52, gyro range@54, frame bytes@56; byte14=1 FIFO reconstructed timing, byte15=1 finalized quality. Saturated uint16 overflow/read-error/queue-drop counters@58/60/62. Old byte14=0 retains original timestamp meaning. Interrupted files may lack finalized counters.

Phone metadata tracks sport/wrist/notes/markers/outcome/provenance; missing legacy context is Not specified. `.csv.analytics.json` is disposable cache. Notes/updates/interruption must preserve raw data and other metadata. ZIP contains original CSV, metadata and summaries. Whole-session and range analytics use full data; charts use peak-preserving envelopes independently. See `docs/SPORTS-APP.md` for metric definitions and metadata conventions.

## Historical evidence and limits

Android v0.7 USB powered check saved 37,223 samples in 31.679667 s (1,174.95 Hz) with zero missing sequences and a 20.331 ms maximum timestamp gap. Verified the first CSV timestamp against the phone clock, timezone metadata and display preview independence. The dim and restore UI flow passed separately. The BLE check reported `phoneScreenOffObserved=false`. A separate host fixture summarized 4,230,001 rows spanning one hour within a 64 MiB Java heap. A full unplugged hardware session remains unverified.

v0.6 OnePlus test:36659 samples/31.213151s ≈1174.44Hz; zero missing sequences; maximum timestamp gap20.764ms.35248 samples during30s phone screen off. Exported CSV count/rate/sequence/provenance independently checked. Short USB-powered test; long-duration battery reliability unverified. Service fullrate check worked locked; separate visible-UI check was blocked by lock screen.

Watch-only examples averaged994–1019Hz with65–87ms gaps. Battery-only archive transfers previously stalled; USB reboot restored transfer but root cause is unresolved. Do not convert USB success into claims of reliable long unplugged sports sessions, uniform acquisition or battery life.
