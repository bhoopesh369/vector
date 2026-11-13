# Vector Motion sports app — v0.3

## Record and review

- **Home** shows the watch status, local recording count, captured time, and recent sessions.
- **Record** lets you choose Badminton, Tennis, Running, or Other and left/right wrist. Connect the watch, then start recording. The active layout groups time, sample count, **Add drill marker** and **Stop & save** together. Mark Rest, Forehand, Backhand, Warm-up, or a custom drill. A marker is tied to the latest received sample's watch timestamp and also stores UTC wall time.
- **Sessions** filters by sport and date, saves notes, and offers analysis and exports. Active files stay hidden until finalized.
- **Analysis** shows measured motion and recording quality. Pinch a chart to zoom, drag horizontally, use the range sliders, or reset to the whole session. Axis toggles affect the plots; statistics always use all six original axes within the selected time range. Choose a drill marker to select that drill up to the next marker.
- Select two readable sessions in **Sessions**, then **Compare selected sessions**. Compare context (wrist/drill/fit) along with measurements; a higher peak does not necessarily mean better performance.

The foreground service owns sensor capture independently of the visible screen. Navigation and activity recreation do not stop it. Bluetooth loss or five seconds without data ends and preserves the session; start a new session after reconnecting. The notification has a stop/disconnect action. No watch firmware update is needed for this app version.

## Measurement definitions

All summaries use full-resolution received CSV samples. The live preview and pixel-bucket chart envelopes never feed the statistics.

| Metric | Definition |
| --- | --- |
| Duration | Last minus first selected watch acquisition timestamp; not phone wall-clock duration |
| Received rate | `(sample count - 1) / duration`; 0 when fewer than two samples |
| Peak acceleration | Maximum Euclidean magnitude of AX/AY/AZ, in g, **including gravity** |
| Peak angular speed | Maximum Euclidean magnitude of GX/GY/GZ, in degrees/second |
| Per-axis min/max/RMS | Component min/max and square root of the mean squared received component values; RMS is sample-weighted |
| Missing sequences | Modular uint32 sequence gaps between selected adjacent samples; sequence wrap is supported |
| Longest timestamp gap | Largest adjacent selected sample interval, in milliseconds |
| Clipped percentage | Percentage of selected samples with the firmware clipping flag |

Selections are inclusive and relative to the first recording sample. Reversed timestamps, duplicate/reversed sequence numbers, malformed rows and non-finite values are rejected with an error rather than silently combined. A malformed interrupted recording stays available for **raw CSV export** even when analysis cannot proceed. This app cannot detect all samples missed inside the IMU before host acquisition; a no-gap CSV is not proof of lossless hardware sampling.

Charts retain each axis's minimum and maximum within each visible pixel bucket, preserving spikes during reduction. Empty buckets are not connected. Chart axes are sensor-native, not world coordinates. No gravity removal, user calibration, swing classification, calories, heart rate, recovery, or racket/shuttle speed is claimed.

## Local files and compatibility

CSV content and BLE protocol v1 are unchanged. Existing recordings remain readable; missing sport/wrist metadata displays **Not specified**. Legacy completion/interruption status text is retained. Historical records without UTC metadata show their saved file date.

Each new CSV has a version1 `.csv.session.json` sidecar for sport, wrist, notes, markers and outcome. Sidecars use atomic replacement, leaving raw CSV untouched. `.csv.analytics.json` is a disposable summary cache, keyed by CSV size, modification time and analytics version. It can be recalculated safely. Session summaries and range calculations run off the main UI thread.

On process restart, `.active` files are finalized as interrupted. If a last CSV row is incomplete, the raw file is preserved and surfaced with an analysis error. Notes updates preserve markers and other metadata. Closing the visible screen does not complete a recording.

**Export CSV** uses Android's document picker. **Export complete session ZIP** includes the unmodified named CSV, `session.json`, and `analytics.json`. Original files stay in app-private storage; uninstalling the app removes them, so export valuable sessions. Files and derived data stay on the phone unless you explicitly export them; no Internet permission is requested.

## Build and checks

Use the existing Android SDK35/build-tools35.0.0 and JDK17, without installing or changing laptop-wide tools. Downloads, signing keys and generated outputs stay under this repository. The original local signing key is reused for updates that preserve app data.

```sh
sh scripts/build-android.sh
sh scripts/test-android.sh
.venv/bin/python -m unittest discover -s tests -v
```

APK: `build/android/vector-motion.apk`. Source components are separated into navigation, screen views, charting, full-resolution analytics, session storage, and the recorder service.

Optional physical-device test harness (signed with the same local development key):

```sh
sh scripts/build-device-tests.sh
/Users/titan/Library/Android/sdk/platform-tools/adb install -r build/android/vector-motion.apk
/Users/titan/Library/Android/sdk/platform-tools/adb install -r build/device-tests/vector-checks.apk
/Users/titan/Library/Android/sdk/platform-tools/adb shell am instrument -w com.vector.motion.tests/.DeviceChecks
/Users/titan/Library/Android/sdk/platform-tools/adb shell am instrument -w -e case visual com.vector.motion.tests/.DeviceChecks
/Users/titan/Library/Android/sdk/platform-tools/adb shell am instrument -w -e case live com.vector.motion.tests/.DeviceChecks
```

The default checks use temporary fixtures and exercise legacy CSVs, metadata/markers/notes, cache invalidation, ZIP contents, empty/malformed files, interrupted partial-file recovery, navigation, and a stale callback after activity destruction. The visual case uses existing saved sessions and checks pinch selection, axis toggles and comparison; it saves app screenshots to the app's external `ui-checks` directory. The live case requires the powered watch and an unlocked phone, briefly sleeps/wakes the phone display, recreates the activity, then intentionally disconnects the GATT connection. It preserves two **Other** recordings with notes identifying them as automated verification. It does not change radio or battery-management settings.

Inspect the instrumentation result text for `PASS`; Android's shell command can exit0 even when an instrumentation process crashes. Remove only the test package when finished:

```sh
/Users/titan/Library/Android/sdk/platform-tools/adb uninstall com.vector.motion.tests
```

## Verified scope

Host checks cover known vectors, sample-weighted RMS, selected ranges, timestamps, uint32 wrap, clipping and preservation of a narrow spike in reduced charts. On the OnePlus13R/Android17, storage/export/UI checks passed and existing recordings remained available after the update. Both independent-review findings (partial interrupted files staying hidden and notes callbacks submitting work after activity destruction) were reproduced and fixed with regression checks.

A battery-powered watch test captured34.28 seconds /3818 samples with no observed sequence gaps while the phone screen remained asleep for30 seconds. Navigation, activity recreation, marker persistence and a controlled GATT-disconnect outcome also passed. The phone remained USB-connected to the Mac for instrumentation. Extended sessions with the phone also unplugged, long-term battery use and real sports validation remain to be checked; do not treat this as a certified workout tracker.
