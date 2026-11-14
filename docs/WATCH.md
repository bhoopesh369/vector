# Standalone motion recording

The factory-based firmware adds **Motion Recorder** to the normal watch launcher. It saves motion to internal flash without a phone or microSD card. Bluetooth transfers completed sessions to the Android app; Wi-Fi file transfer is not implemented.

## On the watch

1. Open **Motion Recorder** (the separate **Recorder** app records audio).
2. Choose sport and wrist, then **Start recording**. Check that the sample count increases.
3. Return Home to use the launcher while recording continues.
4. Reopen Motion Recorder and choose **Stop & save** before downloading.

The screen shows free space and the recording status. The motion archive partition is 6 MiB, with space reserved to finish a file safely. Actual remaining time depends on free space and filesystem overhead. Recording stops when the space limit is reached. There is no automatic deletion of old sessions.

The recorder now uses a fixed layout with a large timer panel, recording status, compact sport/wrist selectors and a prominent Start/Stop button. Saved recordings open on a separate scrollable page; Back returns to the recorder, and Free storage still requires deletion confirmation. Selection is retained while navigating within the app. UI refresh remains independent of recording tasks.

UI update verification: firmware build and flash hash verification passed; boot logs confirm Motion Recorder installation and launcher readiness. Physical layout, touch controls and a short recording with the redesigned screen await user verification.

## On Android

Connect to the watch in Vector Motion, then open **Watch recordings** from Home or Sessions. Refresh the list and download a completed session. Verified downloads appear in Sessions with the selected sport and wrist, ready for analysis and CSV/ZIP export. No Mac is needed for this workflow.

Files remain on the watch after download. Interrupted downloads retain a partial file on the phone and resume after reconnection. Size, session identity and CRC are checked before import; existing phone CSV files are never overwritten. Use **Free storage...** on the watch only after transferring the sessions you want to keep; its confirmation deletes all watch motion sessions.

The phone sets the watch clock when connecting. A session recorded before the clock is known uses its import time in history with a note explaining that its original date is unknown. Sensor-relative timestamps are retained.

## Build and storage

```sh
python3 scripts/setup-watch.py
bash scripts/build-watch.sh
python3 scripts/flash-watch.py --port /dev/cu.usbmodem2101 --dry-run
```

All downloaded tools, components and caches stay inside this repository. The build stages the pinned factory sources into `.cache/watch-project` and applies the tracked overlay from `firmware/watch`. ESP-IDF is pinned to v5.5.5. Wi-Fi starts when WLAN settings are opened; AIChats initializes when opened, rather than consuming recorder memory at boot.

The confirmed 32 MiB flash has the original firmware/resources in the lower 16 MiB. The motion FAT partition uses the verified-erased 16–22 MiB range; older contents above 22 MiB are excluded. The original complete flash backup remains under ignored `backups/`. Flashing checks that backup and restricts firmware writes to the lower 16 MiB. `--update-only` updates bootloader, partition table and app after the initial resource migration. Do not use this mode for the first installation.

A nonblank archive that cannot mount is reported unavailable rather than automatically formatted. On boot, interrupted sessions with a valid header are recovered to the last complete packet and marked interrupted. Buffered samples immediately before power loss may be absent; this is not a guarantee against every flash/filesystem failure. Restoring the complete factory backup replaces the archive as well; export sessions first.

## Protocol

The original live BLE UUID and 26-byte sample format are retained. The additional control characteristic accepts time, session inventory, open, offset-based next-chunk and close commands. A separate notify characteristic carries offset-addressed chunks. Transfer uses one outstanding chunk at a time and pauses live notifications while transferring. Only the watch UI can start/stop recordings or confirm deletion.

Saved `.vmo` files contain a versioned 64-byte header followed by the raw 26-byte samples. A companion `.info` file stores count, CRC and completion status. Android verifies and converts these into the existing CSV columns; motion units and analytics definitions are unchanged. Sequence gaps describe missed software packets, not every possible missed hardware acquisition.

## Verification status

Host framing/import/analytics checks and firmware compilation pass. The update flashed with device hash verification, and USB boot logs confirm Motion Recorder installation, battery monitoring and `Brookesia firmware is ready` without Wi-Fi initialization failure. Android 0.4 is installed on the OnePlus; on-device fixture import, duplicate preservation, storage/metadata, ZIP and navigation checks pass.

The user confirmed offline watch Start/Home/Stop saved a session. Real Bluetooth inventory and download imported 1,550 samples spanning 14.407478 seconds with matching sample count and CRC. Device checks also passed corrupt-completed-download rejection, cancel/reconnect/resume, duplicate-import preservation and the Watch recordings UI. The watch copy was retained.

Long-duration battery, unplugged screen-off standalone recording, power-loss and simultaneous Wi-Fi/AI usage are not yet validated. USB boot logging can reset the watch, so do not open its serial port during a recording.

Follow-up: battery-powered transfers intermittently stalled (first chunk and later inventory responses), then connection attempts failed. Restarting the watch through USB restored transfer; the cause remains unresolved. Verified imports after restart include 449 samples/4.239047s with no sequence gaps or clipping, and 8082 samples/75.977035s at106.36Hz with no sequence gaps,32.664ms maximum timestamp gap and3.39025% clipped samples. Both passed CRC/count verification and cancel/reconnect/resume checks. Successful USB-powered retry does not establish battery-only transfer reliability.

## High-rate capture and mobile streaming

The watch now configures matched accelerometer and gyroscope ODR code2, nominal1793.6Hz, ±16g/±1024dps, LPF mode3. The sensor task drains a128-frame FIFO at a64-frame watermark using bounded CTRL9 handshakes and400kHz I2C, and queues complete batches in PSRAM for buffered storage. The configured sensor rate is not a guarantee of the saved rate. Watch recording displays the measured saved sample rate.

Important QMI8658C constraint: FIFO read mode discards newly arriving sensor samples (manufacturer RevA section8.7). FIFO helps absorb scheduler delays but cannot make this shared-I2C board lossless. Within each batch, timestamps are reconstructed at the nominal sensor period; each batch is anchored to host time at FIFO-read entry. Real inter-batch pauses are retained. These are approximate batch-reconstructed timestamps, not individual hardware timestamps. Sequence gaps expose software queue loss; FIFO overflow/read errors are reported separately. Zero sequence gaps does not imply continuous hardware acquisition.

Original26-byte archive frames and64-byte version1 header remain readable. Header float@48 stores nominalHz; byte14=1 marks FIFO reconstructed timestamps. Byte15=1 marks finalized quality; uint16LE values@58/@60/@62 store FIFO overflows/read errors/queue drops, saturated at65535. Interrupted files may lack finalized counters; their interruption status remains visible. Existing byte14=0 recordings retain their old host-read timestamp meaning.

Legacy preview Bluetooth (UUID0002) uses packet version2 (same layout), its own contiguous preview sequence and at most100Hz notifications (usually one latest reading per FIFO batch). A phone recording from this stream is explicitly a preview, not the full-rate watch archive. Record on the watch and download afterwards for all saved samples. Android0.5 accepts both old version1 and preview version2 packets, rejects preview packets inside raw archives, and preserves nominal rate/timing/quality in session metadata and ZIP exports.

Optional derived export:

```sh
.venv/bin/python scripts/resample_1000.py recordings/raw.csv recordings/derived-1000hz.csv
```

This standard-library tool accepts only contiguous input genuinely faster than1000Hz. It uses a400Hz low-pass windowed-sinc filter, trims unsupported edges, and writes a separately labelled1000Hz CSV plus provenance sidecar. It refuses timestamp/sequence gaps and low-rate input; typical FIFO recordings with read pauses may therefore be unsuitable. It does not fill missing motion or turn100Hz recordings into real1kHz data. Raw recordings are never changed. No continuous1kHz or battery-life claim until verified on the actual device.

First verified high-rate file (318368b180e98b47):42881 samples over44.859623s,955.87Hz saved average,87.029ms maximum timestamp gap, zero flagged FIFO overflows/read errors/queue drops and valid CRC. This demonstrates faster capture but not a sustained1000Hz saved rate. A follow-up build increases the stdio buffer to32KiB in PSRAM and flush/fsync interval to2s to reduce flash overhead; this can lose roughly the most recent two seconds of buffered data on sudden power loss. The subsequent recording rate needs separate measurement. Successful Stop flushes/fsyncs the file and finalized quality metadata.

Post-optimization validation: recording7bcfb5a2eb89dc83 has4744 samples/4.772246s=993.87165Hz,86.930ms maximum gap; recordingecb2ab144c41d00e has6507 samples/6.386410s=1018.72570Hz,65.714ms maximum gap. Both imported with valid CRC/count, finalized quality, zero sequence gaps, FIFO overflows, sensor-read errors, queue drops and clipping. Transfer cancellation/resume and duplicate preservation passed. These short USB-powered tests establish approximately1k samples/s saved throughput, not uniform1ms sampling, continuous lossless acquisition or long-duration battery reliability. Phone remained locked, so this verification used the recording service and did not assert visible UI checks.


With version0.6, the phone can record the full acquired stream directly through UUID0005, using batched raw samples. There is no intentional live-data decimation in that stream. The old reduced preview remains available for older clients. See ANDROID.md for packet framing, loss handling and measured phone CSV rates. Watch-local recording and later download continue to work.
