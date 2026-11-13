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
