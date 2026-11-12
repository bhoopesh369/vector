# Android recorder implementation plan

Goal: build and install the agreed Connect → live readings → Record → CSV export companion, including screen-off recording.
Architecture: retain USB demo as recovery baseline; separate BLE firmware streams versioned 26-byte notifications. Android native Java UI connects through a connectedDevice foreground service, saves CSV privately, exports with the system document picker. No cloud or SD dependency.
Defaults: Android 12+; target SDK35; requested MTU185, minimum29; no automatic reconnection across recording sessions. Connection loss, stale stream, watch restart, or disk errors finalize an interrupted session. Sequence gaps reported. Files flushed every second; process termination may lose the current buffer. Preview runs with notification; Stop disconnects and closes the file. No claim of sports calibration.

- [x] Implement and test protocol decoding (version, length, sequence gaps/restarts, scaling).
- [x] Build BLE firmware with existing IMU settings and retained USB output; preserve factory backup and existing demo binary.
- [x] Implement Android scan, GATT subscription, foreground service, CSV persistence/export, live six-axis charts and interruption reporting.
- [ ] Build APK with installed Android SDK/JDK, install on authorized OnePlus, exercise BLE plus screen-off recording and export; report remaining human checks.

Review focus: denied permissions; duplicate GATT operations; no data while connected; disconnect during file writes; process lifetime and screen-off behavior. Foreground notification exposes Stop. No retries that merge sessions.

Validation: APK compiled/signed/installed; BLE firmware compiled/uploaded with flash hash verification. User confirmed live six-axis readings on Android17. Foreground connectedDevice service observed running; no app crash in checked log window. Packet tests pass. Screen-off CSV recording/export checks are awaiting user result.

Review: independent read-only review found Android12 legacy callback missing and interruption outcome not retained across service recreation. Both fixed in source; rebuilt APK passes signing/protocol checks. Updated APK installation deferred while user may be recording. Existing installed build remains usable for Android17 live recording.

Screen-off investigation: exported phone CSV copied locally as recordings/phone-screen-off-check.csv; 4637 samples span41.634833s, no missing acquisition sequences, max interval10024us. Phone logs show held recording wake lock and screen-off transition within session. User clarified phone screen. No data loss reproduced in this file. Added visible recording duration and per-second notification count to distinguish a paused live chart from saved data. Version0.2 includes review fixes. Long unplugged screen-off check remains pending.
