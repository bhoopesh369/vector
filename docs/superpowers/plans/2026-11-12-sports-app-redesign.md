# Vector Motion sports app redesign

Approved specification: dark athletic Home, Record, Sessions, session analysis and comparison. Sport/wrist/notes/drill markers; local CSV and ZIP export; measured motion analytics only. Existing BLE protocol and CSV remain unchanged; no firmware changes. All dependencies project-local, use existing Java/Android SDK.

## Work and acceptance
- [x] Analytics/storage: full-resolution range statistics, peak-preserving plot reduction, versioned metadata, legacy CSV compatibility, atomic metadata/cache writes. Test known values, gaps/wrap, clipping, malformed/empty/interrupted data, exports.
- [x] Recording: service-owned live buffer, sport/wrist/markers and interruption metadata; keep foreground recording independent of navigation. Test metadata persistence/recording lifecycle on device.
- [x] UI: modular dark Home/Record/Sessions, filters/notes, detail/range/axis controls, zoom/pan/reset, compare, CSV/ZIP export. No fabricated fitness metrics.
- [x] Verification: local suites, APK build, install/update preserving data, device navigation/export, foreground screen-off recording and interruption. Unplugged watch/longer screen-off test requires available powered watch.

Decisions: native Java views and existing SDK tools, no new libraries or SDK installs. Session index uses atomic JSON per CSV; derived summary cache keyed by CSV size/mtime and analytics version. Legacy missing metadata stays Not specified. Durations use watch timestamps. Range statistics include only samples in the inclusive selection and only gaps between those samples. Invalid or reversed timestamps are flagged; no silent bridging across restarts. Active files never exported or analyzed. CSVs remain unchanged. ZIP adds metadata plus analytics JSON. Chart reduction retains each axis minimum/maximum per pixel bucket, while statistics use original samples. Drill marker time is the most recent acquired sample, with wall UTC recorded for traceability.

## Progress

- Host analytics and protocol checks pass: known vectors/RMS, inclusive ranges, gaps/wrap, clipping, malformed order and peak-preserving envelopes. Existing Python capture tests pass. APK builds and verifies its signing.
- Device storage/compatibility/cache/ZIP checks pass using temporary fixtures. Real saved-session UI checks pass for pinch selection, axis toggles and comparison.
- Independent read-only review found two P2s. Partial crash recording was reproduced hidden behind .active; recovery now preserves raw data and exposes an error row/export (regression failed then passed). Stale activity callback was reproduced as RejectedExecutionException; destroyed-activity guard and notes completion guard now pass the same regression.
- Battery-watch live test: navigation + recreation, 30s phone screen off, saved34.28s/3818 samples, zero acquisition sequence gaps, marker persistence, controlled GATT interruption all passed. Phone remained USB-connected for instrumentation; fully unplugged-phone endurance is a remaining manual validation, not a software scope omission.
- Final refinement holds the recording wake lock until metadata is finalized and exposes stopped state only afterward; active recording controls are grouped above charts. Final full device suite rerun passed (storage/lifecycle, native UI, battery-watch BLE/screen-off/interruption); final live capture34.89s/3887 samples with zero sequence gaps. No runtime dependencies, global packages or laptop-wide settings were installed/changed. Existing .idea/.oca files untouched.
