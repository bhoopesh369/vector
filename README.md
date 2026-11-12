# Vector — sports sensor experiments

First milestone: a USB motion demo for the Waveshare ESP32-S3-Touch-AMOLED-2.06. The watch displays acceleration and rotation and streams all six axes to the Mac. The Mac saves CSV. No SD card is needed for this bench test.

## Run

Keep the watch connected by USB. From this project:

```sh
.venv/bin/python scripts/capture.py --seconds 30
```

Tilt the watch gently during capture. The terminal shows live values once per second. Files are saved under `recordings/`: a CSV of readings and a JSON quality summary. The output path is printed when capture ends. Use `--port /dev/cu.usbmodem…` if macOS assigns a different port; use `--output recordings/example.csv` to choose a new filename. Existing CSV files are never overwritten.

## What the data means

| Field | Meaning |
| --- | --- |
| `seq` | Acquisition sequence; gaps expose samples lost between acquisition and capture |
| `t_us` | Microseconds since watch boot, measured when firmware reads the IMU |
| `ax_g`, `ay_g`, `az_g` | Acceleration including gravity, in g |
| `gx_dps`, `gy_dps`, `gz_dps` | Angular velocity in degrees per second |
| `clipped` | 1 when any axis is close to the configured measurement limit |

Configuration: ±16 g acceleration, ±1024 degrees/s rotation, nominal six-axis output rate ~112.1 Hz, vendor low-pass filter mode 3. The library uses the gyro clock when both sensors are enabled. These are sensor-native axes and factory-scaled values; no user calibration or gravity removal is applied.

This is a bench demo, not a lossless sports recorder. There is no IMU FIFO or hardware sample counter yet; timestamps and gaps expose some acquisition/transport delays but cannot prove that every hardware sample was read. Accel and gyro are read sequentially. Fast badminton swings may clip the gyro; its range and sample-rate choices need a separate sports-validation pass.

USB capture requires the Mac to stay connected. No data is stored persistently on the watch. Powering the watch from its battery runs the display but does not save a session. Android BLE and SD-card recording are subsequent milestones.

## Build

The toolchain has been installed locally. To recreate it on an Apple Silicon Mac with Python 3.12+ and Git installed, run `python3 scripts/setup.py`. Initial setup downloads several gigabytes of Espressif tooling.

```sh
sh scripts/build.sh
.venv/bin/python -m unittest discover -s tests -v
```

Project-local tools:
- Arduino CLI 1.6.0 (official ARM64 release, checksum verified).
- Espressif Arduino core 3.3.11, per Waveshare's current instructions.
- Waveshare source commit `30f3b23b3d4bb38d32f7d0cddffb66759668a855` under `.vendor/waveshare`.
- Vendor SensorLib 0.3.1 and Arduino_GFX 1.6.0, used directly from the vendor checkout.
- Python dependencies pinned in `requirements.txt`.

Firmware build output is under `build/vector_motion`. No global Arduino installation is required. Caches, backups and recordings are ignored by Git.

Before any upload, follow [recovery instructions](docs/RECOVERY.md). Uploading replaces the factory demo. USB access from an automated sandbox may require host permission even if the port is visible.

Once the original backup is verified and the firmware is built:

```sh
.venv/bin/python scripts/flash.py
```

This checks the original backup's size and checksum, then writes the four generated firmware images at the Arduino build's offsets. It does not erase the entire flash.

## Live validation — 12 November 2026

Original 32 MiB flash is backed up with a SHA-256 manifest; firmware upload passed device hash verification. Two USB captures produced valid six-axis data at about 111.3 Hz without observed sequence gaps or clipping. The watch appeared mostly stationary; human tilt and display validation remain pending.

On this setup, opening the USB capture restarts acquisition even with DTR/RTS initialized false. Startup diagnostic lines are rejected (13 on each checked run), and each connection starts a new session. No motion calibration or sports accuracy is claimed.

## Android + Bluetooth prototype

The phone companion and BLE firmware are implemented. See [Android usage, build steps and validation limits](docs/ANDROID.md). Phone recording is independent of the Mac; the watch still needs power and a Bluetooth connection to the phone. SD recording is not implemented yet.
