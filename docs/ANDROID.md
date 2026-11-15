# Android recording and display

Start in **Record → Connect watch → Start recording** for a full-rate session. The phone writes the CSV into its own storage. Leave the watch's standalone recorder idle for a phone-only session. At about 1,175 samples/s, budget roughly 350–400 MB of phone space per hour for the CSV, plus room for any export.

New phone CSVs use Unix microseconds in `t_us`. The phone anchors the first BLE packet to its wall clock at receipt and uses the watch's sample intervals for the rest of the session. Absolute times include Bluetooth delivery latency. Newly imported watch archives with a valid clock use their saved start time, which has one-second resolution. Sessions display Asia/Kolkata time. Existing CSVs retain their original timestamps.

On v0.8, **Dim watch display** lowers the watch AMOLED brightness to zero. Use **Restore watch brightness** to bring back the previous level. The watch also attempts to restore brightness on disconnect. The buttons require updated watch firmware. Recording and Bluetooth streaming continue while the watch is dimmed. Use the phone power button to control the phone screen.

The phone computes whole-session statistics from every saved row using bounded memory. Sessions above 250,000 rows offer whole-session statistics and raw CSV or ZIP export. Export those sessions for interactive charts.

## v0.7 verification

The Linux laptop and CPH2661 phone saved 37,223 samples over 31.68 seconds at about 1,175 Hz with zero sequence gaps and a 20.33 ms largest timestamp interval. The check verified absolute timestamps, metadata and full-rate saving. It reported `phoneScreenOffObserved=false`. Dim and restore passed separately. An hour-sized synthetic CSV with 4.23 million rows was summarized with a 64 MiB Java heap on the laptop. Unplugged endurance remains unverified.

## Full-rate mobile recording introduced in v0.6

See [sports app usage, analytics definitions and validation](SPORTS-APP.md) for the current Home / Record / Sessions interface. The sections below document the original prototype and BLE protocol, which remains compatible.

Android has preferred the full-rate batched Bluetooth characteristic on compatible watches since v0.6. **Record → Connect watch → Start recording** saves every received acquired sample directly on the phone; charts remain a reduced display preview. Full-rate sessions are labelled `bluetooth_fullrate`, with `-fullrate` in CSV filenames. Legacy watches still work at their original stream rate; version0.5 preview-only firmware remains explicitly labelled as preview. Underlying watch FIFO timing gaps remain.

Version 0.5 distinguished reduced-rate Bluetooth previews from full-rate watch archives, preserves sensor-rate/timing/acquisition-quality metadata, and accepts old firmware. With version0.5 firmware, full-rate recording starts on the watch; phone recordings are previews. Version0.6 adds full-rate streaming directly to the phone.

Version 0.4 added **Watch recordings** for inventory, resumable Bluetooth download and verified import from the factory-based standalone recorder. See [watch firmware usage and verification status](WATCH.md). Wi-Fi transfer and microSD recording are not implemented. Existing minimal BLE firmware can still stream live samples but cannot provide saved watch sessions.

## Historical Android prototype

Installed and connected on OnePlus 13R (Android 17). User confirmed live readings on 12 November 2026. The user exported a phone recording after a screen-off check. Inspection found 4,637 samples over 41.635 seconds at 111.35 Hz, no sequence gaps, and a largest adjacent sample interval of 10.024 ms. Phone power logs show the recording wake lock held across the session and a screen-off transition during it. This demonstrates continuity for this short USB-connected test; extended unplugged screen-off operation remains to be checked.

## Use

1. Keep the watch powered and nearby; open **Vector Motion** on the phone.
2. Allow Nearby devices and notifications. Turn on phone Bluetooth, then tap **Connect**.
3. Wait for **Connected • live motion**, then tap **Record**. The app labels sessions badminton.
4. Turn the screen off if desired. An ongoing notification exposes **Stop & disconnect**.
5. Tap **Stop & save** when finished, then **Export saved CSV**. Choose the recording and a destination in Android's document picker.

The CSV remains in app-private storage until exported. Uninstalling the app deletes these originals. Export after each useful session. A text sidecar internally records completion/interruption, received count, sequence gaps and clipped values. This prototype's export currently exports the CSV only.

Bluetooth loss, five seconds without samples, or a reversed watch timestamp ends the session and preserves received data. Reconnect and start a new recording. The watch does not retain samples without SD recording. Force-stop or process termination can lose up to the current buffered interval; an active-session marker is reconciled on next launch. Long-duration reliability and battery use have not been measured.

Display updates at 5 Hz; recording consumes every received notification at approximately the IMU rate. Display charts show a rolling preview with separate auto-scaled accelerometer and gyroscope panels. Readings use factory scaling, with no user calibration or sports interpretation.

## Build and install

Requirements: macOS with Android SDK platform35/build-tools35.0.0 and JDK17. Build uses native Android Java APIs and SDK tools, without Gradle dependencies. The paths default to this workstation; override `ANDROID_SDK_ROOT` and `JAVA_HOME` for another installation.

```sh
sh scripts/build-android.sh
/Users/titan/Library/Android/sdk/platform-tools/adb install -r build/android/vector-motion.apk
sh scripts/build-ble.sh
.venv/bin/python scripts/flash.py --variant vector_ble --port /dev/cu.usbmodem101
```

Use the watch's current port. Flash script requires the saved factory backup and manifest. Android APK is signed with the local development key in `.tools/vector-debug.jks`; keep this key to update the app without uninstalling it. The APK is a local development build, not a published Play Store release. App supports Android12+ and targets SDK35.

Protocol checks:

```sh
mkdir -p build/protocol-tests
javac -d build/protocol-tests android/src/com/vector/motion/MotionPacket.java android/tests/MotionPacketTest.java
java -cp build/protocol-tests MotionPacketTest
```

## Bluetooth protocol v1

Device advertises service `7b8e0001-6f27-4b8e-a9a1-3cd405ee1920` as **Vector Motion**. Notify characteristic `7b8e0002-6f27-4b8e-a9a1-3cd405ee1920`; CCCD enables notifications. Android requests MTU185; at least29 is required for a 26-byte payload. No pairing code or bonding is configured in this prototype.

| Byte offset | Field | Encoding |
| --- | --- | --- |
| 0 | Version | uint8, 1 |
| 1 | Clipping flag | uint8, 0 or 1 |
| 2 | Acquisition sequence | uint32 little-endian, wrap permitted |
| 6 | Microseconds since watch boot | int64 positive little-endian; host read timestamp |
| 14 | Acceleration X/Y/Z | 3 × int16 little-endian, divide by1000 for g |
| 20 | Rotation X/Y/Z | 3 × int16 little-endian, divide by16 for deg/s |

Range and sampling match the USB demo: ±16g, ±1024deg/s, nominal112.1Hz. Queue and notification delivery are best effort; sequence gaps expose some loss. No hardware FIFO means missing hardware samples cannot all be detected. USB capture remains available but opening the serial port can restart the watch; avoid opening it during phone recording.

Android background-service design follows [Bluetooth background guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background) and the [connected-device foreground service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).

## Version 0.2

Recording progress now shows elapsed sample time and saved-sample count on the screen and foreground notification (updated after the one-second buffer flush). Live graphs pause while the activity is hidden; the service writes independently. Interrupted outcomes persist across service recreation and appear in a completion notification. Legacy Android12 notification callbacks are supported.


## Full-rate streaming protocol (v0.6)

Optional notify characteristic `7b8e0005-6f27-4b8e-a9a1-3cd405ee1920` carries8-byte batch headers followed by1–18 original26-byte version1 samples. Header: byte0=0xA3,byte1=1,byte2=frame count,byte3=flags(bit0 queue loss),bytes4–7=uint32LE cumulative sender-queue dropped samples. Count/length/flags and every sample are validated. Preview frames are refused in this stream. Acquisition sequence numbers and timestamps are preserved. Confirmed transport gaps/queue overrun interrupt phone recording visibly. This is not a claim against all possible radio or acquisition loss.

Firmware has a bounded4096-sample PSRAM transport queue and a dedicated sender, with notification backpressure retries. Requested ATT MTU517 yields18-sample values that fit two251-byte link-layer data packets; negotiated smaller MTUs use smaller batches. DLE251, fast connection intervals and2M PHY are requested with normal peer fallback. Actual throughput must be measured. Saved-file downloads pause/reset streaming queues and epochs; phone recording and archive download remain mutually exclusive.

OnePlus validation:36659 saved samples/31.213151s=1174.44Hz, zero sequence gaps,20.764ms maximum timestamp gap.35248 samples arrived in a30-second interval with phone screen off at both boundaries. Full-rate metadata and reduced chart data were checked. Watch/phone both used USB power; long-duration battery reliability remains unverified. Host framing/legacy/resampling checks and both builds pass. Additional visible UI validation was skipped while phone was locked.


## Connected-watch dim check — 15 November 2026

The connected OnePlus initially had v0.6 and the watch did not advertise dim support. After updating the app and watch without removing saved sessions, a live BLE check acknowledged Dim and Restore, added14069 samples during12s of dimming, and saved20346 samples over17.322162s (1174.507Hz), with zero sequence gaps and20.491ms maximum timestamp interval. Disconnect/reconnect confirmed restored display state; the user separately confirmed the screen visibly darkened and returned to its previous brightness. The temporary test package was removed. This was a short USB-connected check, not a battery endurance result.
