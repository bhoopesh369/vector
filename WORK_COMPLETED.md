# Work completed: video/IMU review and event labelling

Updated 15 November 2026. This change adds a local browser workflow for reviewing
watch motion against video and preparing manually checked racket-swing labels.
Existing upstream watch/Android work on `main` was preserved and fast-forwarded
before this change. No device was flashed, reset, formatted or reconfigured.

## Delivered pages

- `index.html`: synchronized review, one video beside sensor values/plots, camera
  selection, playback speed, actual audited frame stepping, editable alignment,
  draggable video tracks and event markers.
- `imu-explorer.html`: six separate axis plots, acceleration/rotation magnitudes,
  inter-row timing, raw/processed comparison, zoom/pan, per-row inspection,
  selected-window statistics, gap/near-limit markers and actual video PTS/DTS tables.
- `imu-explorer-legacy.html`: the original standalone explorer, including its
  embedded historical recording, preserved separately.

## Run after cloning

Videos remain local and are excluded from Git. No Git LFS setup is needed.
Keep originals in `samples/videos/`, or select them through the viewer.

```sh
sh scripts/setup-analysis.sh
python3 scripts/serve_sync.py
```

Open `http://127.0.0.1:8000/`. The server binds only to localhost and supports
byte-range requests for video seeking. Analysis uses the project-local NumPy/SciPy
environment. Fresh clones include CSVs, metadata and reports, but no video files,
generated plots/screenshots or NumPy bundles. Existing local files are preserved.
No system packages or global Git configuration changed.

1. Load stitched watch data and sample videos.
2. Use the rough placement only as a starting estimate, then refine against the
   same visible physical event and sensor burst.
3. Review slowly, choose a label/category, and click **+ Marker** or press **M**.
4. Export labels CSV for analysis and markers JSON for backup/restoration.

## Exactly how video alignment works

The combined sensor clock is:

```text
watch_seconds = (t_us - 1418928394) / 1000000
video_seconds = watch_seconds * camera_scale + camera_offset_seconds
```

For a camera offset of -0.9 s and scale 1, watch time 2.174 s displays video time
1.274 s. Increase the offset to advance video relative to the data; decrease it to
delay video. Dragging a clip left advances it; dragging right delays it.
**Align here** calculates offset from the sensor cursor and the camera preview.
Two matched anchors estimate a constant relative clock rate and offset:

```text
scale = (video_time_2 - video_time_1) / (watch_time_2 - watch_time_1)
offset = video_time_1 - watch_time_1 * scale
```

Video-anchored markers store a source frame/time. Their watch time is resolved as
`(video_time - offset) / scale`, so they follow future alignment changes. An
IMU-anchored marker stays at its original watch time. The readout selects the
nearest acquired row within 20 ms; otherwise it displays a gap. Each video plays
only inside its aligned interval. Original data are never shifted or overwritten.

The starting placements are provisional: IMG_3303 near watch session one,
IMG_3304 near session two, and IMG_3302 earlier than the available watch data.
They are not a validated correlation or a frame-accurate calibration.

## Included recordings and tools

Following the updated request, this change includes CSVs, session metadata,
text/JSON analysis reports and frame tables from `samples/` and `recordings/`.
Video originals and generated binary artifacts are ignored and remain on disk.
The additional export under `samples/vector-motion-sessions/` is also included
(14 full-rate session CSVs, metadata and session index). They remain separate recordings, preserving gaps and clock identities. All 14
have now been analyzed (1,941,960 samples); results and six new MP4 clock checks
are in recordings/new-session-review/. These checks do not establish physical
sensor accuracy or labeled swing ground truth.

`.tools`, `.cache`, `.vendor`, `.venv`, build outputs, device backups, signing
material and OS metadata remain excluded. Historical upstream firmware/Android
files remain intact. Files under `recordings/marker-qa/` are temporary validation
fixtures, not ground-truth swing labels; browser test markers were removed.

| Watch segment | Start on combined clock | End | Rows |
| --- | ---: | ---: | ---: |
| 3e23360e5c8d89d3 | 0.000000 s | 67.986941 s | 68,435 |
| Inter-session gap | 67.986941 s | 91.377595 s | 0 |
| 8017f15ecd91ee46 | 91.377595 s | 107.111673 s | 15,846 |

`recordings/stitched/watch-combined.csv` preserves all 84,281 original rows and
original watch timestamps, plus provenance columns. Its filtered counterpart keeps
raw backup axes. The 23.390654-second pause is preserved without invented samples.

- `scripts/stitch_imu.py`: stitches compatible, non-overlapping sessions on their
  existing monotonic watch clock; rejects sequence resets and existing outputs.
- `scripts/filter_imu.py`: offline, fourth-order zero-phase Butterworth filtering
  of all six axes, separated at recording gaps, plus optional constant gyro bias
  subtraction from an explicitly verified stationary interval.
- `scripts/filter_request.py`: local JSON bridge for interactive filtering.
- `scripts/audit_timestamps.py`: extracts stored packet PTS/DTS in presentation
  order and optionally decodes every source frame to verify counts.
- `scripts/review_videos.py`: sampled contact sheets and exploratory image
  differences. Camera movement/lighting make those differences unsuitable for
  claiming automatic wrist-to-IMU alignment.
- `scripts/serve_sync.py`: localhost viewer and filtering endpoint.

## Timestamp verification and corrected source provenance

The newly fetched upstream source now contains the actual FIFO/archive/import
implementation. Earlier reports said that source was unavailable in the old
checkout; the current audit and visualizer text have been corrected.

- QMI FIFO frames have no independent per-frame hardware timestamp. The firmware
  reconstructs within-batch spacing at nominal 1793.6 Hz and anchors batches to
  processor uptime (`high_rate.hpp`, `qmi_fifo.hpp`, `motion_service.cpp`).
- The watch archive header stores whole-second `startedUnix` and uptime
  `bootStartUs` at recording start. Android imports `startedUtc` from that header,
  with an import-time fallback if the watch calendar clock was unset.
- The Android watch-sync command sets watch time from phone Unix time truncated
  to seconds. BLE setting latency is not measured.
- `startedUtc` is recording-start metadata, not the first CSV sample UTC. The
  supplied CSV/session exports omit the original header's `bootStartUs`; exact
  first-sample UTC and cross-device capture synchronization remain unestablished.
- The illustrative 622.405 ms implied boot-time disagreement assumes
  `startedUtc == first sample UTC`; it must not be interpreted as clock drift.
- All 8,927 video frame counts were verified by decoding. Their stored PTS use
  a 1/2400-second time base and vary from 19 to 21 ticks between frames. PTS is
  presentation timing, not a verified physical exposure-start UTC. Extra Apple
  timed-metadata streams were not decoded.

| Video | Verified frames |
| --- | ---: |
| IMG_3302.MOV | 1,567 |
| IMG_3303.MOV | 6,609 |
| IMG_3304.MOV | 751 |

## Event labels and algorithm readiness

Labels support racket swing, not a swing, uncertain and sync reference, plus
custom text/notes. Markers appear on plots/tracks, can be jumped to, edited, moved,
deleted and restored with Undo. Markers and alignment are saved in local browser
storage. Export JSON as backup; CSV includes label time, source video/frame,
nearest original IMU row/timestamp/sequence/session and raw axes. Gap events have
empty IMU measurements. Import validates recording identity and restores alignment.

The workflow prepares annotations; it does not automatically validate them.
Visually check the alignment and each positive/negative event before treating the
CSV as ground truth. Video can validate event timing/counts, but numerical six-axis
accuracy needs additional calibration/tracked references. Clipped readings,
missing acquisitions and segment-edge filter artifacts cannot be recovered.
Current source config is +/-16 g and +/-1024 deg/s; near-limit flags do not clamp
at the warning threshold. A wider supported gyro range has not been established.

## Verification

Run:

```sh
.tools/imu-analysis/bin/python -m unittest discover -s tests -v
node tests/test-sync.cjs
node tests/test-imu-analysis.cjs
node tests/test-markers.cjs
```

Checks cover capture preservation, gap-aware filtering, bias/gravity handling,
zero-phase behavior, the local filter bridge, byte-range serving, timestamps,
stitching, raw/processed separation, CSV exports and video/IMU marker anchoring.
The fast-forward also brings the upstream resampling checks into the Python run.

Browser checks covered side-by-side review, actual frame-time inspection,
interactive filtering, timeline dragging, markers following alignment edits,
persistence, label editing, Undo, keyboard marking, gap labels, CSV download and
JSON import. Physical watch/phone and long battery-session behavior were not
retested during this web-viewer work.


## New import and alignment work

- Added lazy session library, background CSV parser, folder/sidecar import,
  session chooser in sticky transport and collapsible import/evidence UI.
- Added bounded local MP4/MOV parser: skips multi-GB media payloads, reads actual
  sample timing/composition/edit lists, and recognizes QuickTime creation dates.
- Added clock convention detection. All six new Android creation tags describe
  recording end; use explicit-timezone filename start instead. Conflicts/missing
  origins are flagged. File modification time never drives alignment.
- New epoch CSV processing subtracts timestamps before unit conversion, preserving
  microsecond spacing. Existing marker identities tolerate only sub-microsecond
  duration-rounding changes, retaining all other recording identity fields.
- Automatic alignment sets offset/overlap, excludes unrelated clips and preserves
  saved manual adjustments. Explicit reapply and two-event drift fitting remain.
- Python checks: 28 passing in the project-local analysis environment. Five Node
  suites pass, including reproduction of all 8,927 audited MOV PTS. New short MP4
  frame count matches FFmpeg (362); its edit-list presentation offset differs by
  1.533 ms from FFmpeg normalization and is documented in the review report.
- Browser checked largest 419,670-row session, matching clip placement and shared
  playback. Full decoding of all six new MP4s and physical calibration were not
  performed. All nine video originals remain local; no LFS filters or installer
  are needed. The unpublished media commits were replaced before pushing, so
  video pointers and LFS objects are not part of outgoing main history.

## Interactive graphs and replay zones

- Sync Studio now has linked chart zoom/pan, a full-recording overview, hover
  readouts/crosshairs and drag-to-select replay zones. Exact numeric boundaries,
  zone zoom, fit, keyboard controls and playhead following are available.
- Replay synchronizes video and IMU inside the selected interval, loops on request
  and stops at its endpoint otherwise. Named zones persist per recording and are
  included in alignment exports. Event markers and manual video alignment retain
  their existing behavior. No video files are added to Git.
- The detailed analyser now selects windows by dragging plots and can replay/loop
  its selected window. Both viewers render connected, peak-preserving traces and
  cache signal backgrounds separately from cursor overlays. Raw data is unchanged.
- Added dependency-free graph-core checks for pointer-anchored zoom, bounded pan,
  reversed selection, stop/wrap boundaries, ordered extrema and line breaks across
  gaps/non-finite values, plus dense-data reduction without losing peaks.
- Verification: 28 Python tests and six JavaScript suites pass. Browser checks
  covered the 419,670-row session, video/IMU replay and looping within 9–10 s,
  stopping at 10 s with looping off, chart dragging, keyboard zoom/pan, zone
  persistence after reload and alignment JSON zone export. The detailed analyser
  was checked with 84,281 rows, drag selection and window looping/stopping.

## Experimental badminton mode

- Researched wrist/racket badminton sensing before coding. Source links, method,
  assumptions and validation plan are in docs/BADMINTON-ANALYSIS.md.
- Added explicit Badminton mode, whole-recording/replay-zone analysis, worker-based
  acceleration-change + rotation candidates and plot overlays. Original CSVs and
  manual alignment are preserved. The 254.526 s example produces a nearby proposal
  at 254.532 s; contact accuracy has not been established.
- Added provisional hit count, separate human-confirmed contacts, review backlog,
  rate/spacing and measured wrist peaks. Weak/medium/strong are within-analysis
  wrist-motion bands, not force or shuttle speed; near-limit events stay unrated.
- Added per-event video replay and confirm/reject/uncertain review, manual missed
  contacts, browser persistence and CSV/JSON exports with provenance/settings.
- Defaults propose 68 events in the 419,670-row sample; 21 are near-limit. This is
  an unlabelled detector output, not a verified hit total. No video, recordings or
  generated analysis artifacts were added to commits for this feature.
- Verification: 28 Python tests and nine JavaScript suites pass, including sports
  detection/negative fixtures, recoverable worker errors, CSV escaping/UTC
  provenance and storage restoration. Browser checks covered the 419,670-row
  session, confirm/reject count updates, a one-candidate selected zone at 254.532 s,
  duplicate-add prevention and synchronized event replay. Test review changes
  were returned to pending; no contacts were asserted as ground truth. Browser
  console showed no errors. The screenshot remains local under .cache.

## Review-guided false-hit screening

- Read the user-supplied analysis/vector-badminton-analysis.json without modifying
  or staging it. It contains 50 confirmed contacts / 18 rejected proposals. The
  first three are reported arm circles; other negatives are reported mostly
  shuttle pickups, without exact per-event activity subclasses.
- Added matching-recording review import, local-file loading and a small personal
  motion-screen tree trained only on human labels. Source CSV features are
  recomputed. Existing alignment, sensor data and review decisions are preserved.
- Flags default to review-only; optional exclusions never remove confirmed hits.
  Flagged unreviewed events remain in the review queue. A last learned model can
  be explicitly reused on another recording without transferring review labels.
- Same-label fit flags 18/18 non-hits and 5/50 contacts. A 45-event / 23-event
  chronological diagnostic keeps 15/16 known contacts and flags 5/7 non-hits.
  These are not independent validation or session hit-recall measurements.
- Added a reproducible local CSV/review audit and tests for identity-safe imports,
  chronology, bounded trees, review-only behavior, overrides and exported flags.
  Browser testing of the new controls was blocked by declined localhost access;
  no alternate browser route was attempted. User review data and audit artifacts
  remain local and uncommitted.
- Verification: 28 Python tests and ten JavaScript suites pass. The audit
  recomputes all 68 matching proposal features from the CSV before fitting; no
  labels were inferred for missing events. Exports preserve flags, inclusion
  decisions and confirmed overrides. The user's separate AGENTS.md edits and
  analysis/ review file are intentionally left unstaged.

## Saved Sports tracking / Badminton filter

- Saved the aggregate, reproducible learned filter as the runtime asset
  web/sports-tracking/badminton/hit-filter.js. It contains model parameters and
  source diagnostics, not per-event review labels or recording samples.
- Badminton now automatically loads a saved/per-recording filter (with the bundled
  filter as fallback) and excludes flagged unreviewed proposals. This migrates
  the old review-only default once. Confirmations always override filtering, and
  explicit new opt-out settings persist across reloads.
- Filtered hits are the default list/plot view and counter. Added a filtered-hit
  CSV export while retaining the all-proposal export and flagged-event review.
  Relearning saves and activates the Badminton filter. Raw signals and timestamps
  are not filtered or overwritten by this candidate screen.
- Added controller/preset checks for default loading, legacy migration, persistent
  opt-out, unchanged bundled parameters, confirmation overrides and separate
  filtered/full CSV exports. Browser access remains unverified after the previous
  localhost permission decline; no browser workaround was attempted.
- Validation: 28 Python tests and eleven JavaScript suites pass, including the
  saved-preset/controller checks. The source review JSON and AGENTS.md edit remain
  unstaged; no recordings or generated audit outputs are part of this change.
