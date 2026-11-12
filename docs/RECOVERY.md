# Restore the watch

The original full 32 MiB flash backup is kept locally under `backups/`, excluded from Git. It may contain device settings, so do not publish it. The complete 32 MiB backup and its SHA-256 manifest have been saved. The initial 64 KiB matches an earlier independent probe. An actual restore has not been performed.

Use the project Python environment and esptool 5.3.0 with `--stub-version 2`. The legacy stub stalled on this watch during reads; the newer stub completed the probe successfully.

From the repository root, first verify the backup:

```sh
shasum -a 256 -c backups/factory-original-2026-11-12.bin.sha256
```

Restore the original flash (this replaces the current firmware and settings):

```sh
.venv/bin/python -m esptool --chip esp32s3 --port /dev/cu.usbmodem1101 --stub-version 2 write-flash 0x0 backups/factory-original-2026-11-12.bin
```

If the port changes, use the `/dev/cu.usbmodem…` port actually shown by `ls /dev/cu.*`. Close any capture process before flashing.

If USB is unavailable, use Waveshare's recovery sequence: hold BOOT, connect USB, then release BOOT. With an installed battery, if reconnecting USB does not reboot the watch, power it off first and hold BOOT while powering it back on. After flashing, power-cycle the watch if it stays in download mode. Do not alter eFuses or USB pin assignments.

The separately downloaded official factory image is available at `.vendor/waveshare/firmware/ESP32-S3-Touch-AMOLED-2.06_FactoryOnly_20261016.bin`. This is a vendor demo image, not a backup of this watch's settings. Prefer the full original backup. Both are images intended for flash offset `0x0`.

Recovery availability is established by the saved image and verified flash contents; an actual restore must be reported separately if performed.

References:
- https://docs.waveshare.com/ESP32-S3-Touch-AMOLED-2.06/Instructions-For-Use
- https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-2.06/tree/main/firmware
