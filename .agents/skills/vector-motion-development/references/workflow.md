# Laptop, verification and Git

## Devices and data preservation

Historical identifiers: OnePlus13R/Android17 serial `953b5053`; watch `/dev/cu.usbmodem101`. On the Linux laptop, ADB was under `$HOME/Android/Sdk/platform-tools/adb`, the phone enumerated as `CPH2661` and the watch as `/dev/ttyACM0`. Rediscover devices/ports; an old ready reply does not establish present availability.

Opening watch serial can reset it. Do not open serial during recording; use one serial owner and inspect diagnostics before/after. Stop & save before downloading. USB power changes test conditions.

For phone discovery, check `adb devices -l`, USB debugging and authorization. A scoped ADB-server restart previously restored discovery; it affects other ADB clients and is optional. Do not alter global settings or uninstall the product app. Nearby devices and notification prompts are phone-side permissions.

Watch has6MiB motion archive at16–22MiB of confirmed32MiB flash. Preserve ignored factory backup and flash-script checks. Initial resource migration differs from update-only flashing. Do not automatically format a nonblank archive. Complete factory restore replaces recordings. Buffered writes can lose roughly the latest2s after sudden power loss; Stop & save flushes.

## Build/check command map

Run from the repo root and keep outputs local. The scripts default to the historical macOS SDK and JDK paths. Set `ANDROID_SDK_ROOT`, `JAVA_HOME` and prepend `$JAVA_HOME/bin` to `PATH` when needed. Android v0.7 was built on Linux with Android Studio JBR 21, build tools 35.0.0 and the installed API 36 platform exposed at `platforms/android-35` inside a repo-local SDK shim. The APK still targets API 35. Watch builds use local ESP-IDF 5.5.5.

| Work | Command |
| --- | --- |
| Android analytics/protocol/import | `bash scripts/test-android.sh` |
| Python capture/resampling | `.venv/bin/python -m unittest discover -s tests -v` |
| Android APK | `bash scripts/build-android.sh` |
| Factory-based watch firmware | `bash scripts/build-watch.sh` |
| Device test APK | `bash scripts/build-device-tests.sh` (product build first) |
| Migrated watch update | `python3 scripts/flash-watch.py --port <observed-port> --update-only` |

C++ tests: `firmware/watch/tests/{format_test,high_rate_test,stream_batch_test}.cpp`. Compile relevant tests with `c++ -std=c++17 -Ifirmware/watch/components/MotionRecorder/include <test.cpp> -o .cache/<test-name>` then execute. Create local output directory if needed. If sandbox blocks IDF process inspection or Git index writes, request narrow execution access for the authorized operation rather than changing system settings.

Product APK `build/android/vector-motion.apk`; test APK `build/device-tests/vector-checks.apk`. Reuse `.tools/vector-debug.jks` for updates; never print/commit it. Example physical check after both builds (replace serial with observed device):

```sh
adb_bin=/Users/titan/Library/Android/sdk/platform-tools/adb
phone_serial=953b5053
"$adb_bin" -s "$phone_serial" install -r build/android/vector-motion.apk
"$adb_bin" -s "$phone_serial" install -r build/device-tests/vector-checks.apk
"$adb_bin" -s "$phone_serial" shell am instrument -w -e case fullrate com.vector.motion.tests/.DeviceChecks
```

The fullrate case connects, requests panel sleep during a short BLE recording and retains its test file. It checks CSV clock anchoring, timestamp metadata, throughput, missing sequences and preview independence. Read `phoneScreenOffObserved` separately from PASS. Confirm a long-session summary on a large fixture separately from a physical endurance run. It changes screen/connection state; run only within the task's scope with devices ready, without interrupting user recording. Inspect emitted PASS/FAIL: shell may exit0 after instrumentation crash. Visible UI cases need unlock. Other cases in `docs/SPORTS-APP.md`. Remove only `com.vector.motion.tests` afterward; product uninstall deletes originals.

For recording changes verify saved count, duration/rate, modular sequence gaps, max timestamp gap, clipping, metadata, and CRC/count for archive import. Separate source tests, fixtures and actual device evidence. Preserve evidence under ignored `.cache`/`recordings`. Both devices unplugged with phone screen off remain necessary before claiming long sports-session readiness. Documentation-only changes do not require repeating hardware tests.

## Git

Work/push on **main** by default. Inspect status, branch, ancestry and remote. Preserve unrelated staging and use explicit intended paths. No force-push or GitHub CLI. Conventional messages; author and committer via repo-local settings or:

```sh
 git -c user.name=Bhoopesh -c user.email=bhoopesh459@gmail.com \
     commit --only <intended-paths> -m 'docs: document Vector Motion development workflow'
```

Keep personal authentication procedures and machine-specific credentials outside tracked documentation.

If GitHub returns a repository permission 403, recheck the remote and report the required write access. A new branch on the same remote still requires repository write access. Verify push with `git rev-parse HEAD` versus `git ls-remote origin refs/heads/main` and inspect status for preserved staging. If a push hangs, cancel it before retrying; remote SHA alone does not prove command completion. A local commit is not a successful push.
