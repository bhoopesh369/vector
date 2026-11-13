# Current app: v0.4 watch imports and sports redesign

See [sports app usage, analytics definitions and validation](SPORTS-APP.md) for the current Home / Record / Sessions interface. The sections below document the original prototype and BLE protocol, which remains compatible.

Version 0.4 adds **Watch recordings** for inventory, resumable Bluetooth download and verified import from the factory-based standalone recorder. See [watch firmware usage and verification status](WATCH.md). Wi-Fi transfer and microSD recording are not implemented. Existing minimal BLE firmware can still stream live samples but cannot provide saved watch sessions.

# Vector Motion Android prototype

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
