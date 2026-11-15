# Vector — sports sensor experiments

Vector Motion for Android records the full BLE stream directly to phone storage on compatible watches. The app shows session times in IST and offers **Dim watch display** on compatible firmware. It summarizes long sessions from the full CSV and supports CSV/ZIP export. See [Android recording and setup](docs/ANDROID.md) and [sports app analysis](docs/SPORTS-APP.md).

The new factory-based watch firmware adds internal-flash recording and later Bluetooth download while keeping the normal launcher. See [standalone watch usage and current verification status](docs/WATCH.md). The USB and minimal BLE demos below are earlier firmware alternatives.

The initial milestone is a USB motion demo for the Waveshare ESP32-S3-Touch-AMOLED-2.06. The watch displays acceleration and rotation and streams all six axes to the Mac. The Mac saves CSV. No SD card is needed for this bench test.

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

This earlier USB demo requires the Mac to stay connected and does not store data on the watch. Use the factory-based Motion Recorder firmware for standalone recording; microSD recording is not implemented.

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

Firmware build output is under `build/vector_motion`. No global Arduino installation is required. Caches, backups and tool/build outputs are ignored by Git. CSV recordings and metadata are included; video originals and generated binary review artifacts stay local and are ignored by Git.

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

## Video + IMU sync studio

Start the local-only server with video byte-range seeking:

```sh
python3 scripts/serve_sync.py
```

Open [the local sync studio](http://127.0.0.1:8000). Refresh after changes. The
original sensor explorer is preserved as `imu-explorer-legacy.html`;
`imu-explorer.html` opens the detailed signal lab. Video originals are
in `samples/videos/`; the web viewer processes files locally. CSV recordings and metadata are versioned. Videos stay local and are excluded
from Git; select them in the viewer or place originals in samples/videos/.

1. Click **Load stitched watch data** and **Load sample videos**.
2. Optionally click **Apply rough reviewed placement**. This places IMG_3303 near
   the first watch session and IMG_3304 near the second. IMG_3302 appears earlier
   than the available IMU data and is excluded from playback. These are provisional
   visual/metadata estimates, not measured frame-accurate offsets.
3. In **Alignment tracks**, drag each blue clip left or right against the fixed
   green IMU segments. Zoom to 10 s or 30 s for finer adjustment. Arrow keys nudge
   a focused clip one frame; Shift + arrow nudges 100 ms. Moving right delays the
   clip on the sensor timeline. The playhead stays at the same sensor time.
4. For exact manual placement, seek to a distinctive sensor event. Adjust the
   camera's **Preview / s** or preview frame arrows to the same event, then
   **Align here**. **Anchor 1** and a later **Anchor 2 / fit drift** can estimate a
   constant clock rate. Audited sample videos step by actual stored PTS; other videos use the configured playback fps.
5. Play, scrub, or change speed. The shared timeline runs across both sessions and
   the gap. Each video plays only inside its own aligned interval. Outside a clip,
   its preview is covered with “No video at this time”. IMU readouts show GAP when
   no sample is within 20 ms. Buffering pauses the group; press Play to resume.
6. **Signal** switches processed/original axes. **Export alignment JSON** preserves
   offsets, scales, inclusion and anchors. Settings are in memory until exported;
   alignment import is not implemented.

Additional CSVs can be loaded separately using **+ IMU CSVs**. Standard `t_us` and
six-axis columns are recognized automatically. Other single CSVs can use column
mapping and explicit timestamp units. If there is no timestamp, choose **Row
index / rate**; the fallback defaults to 1200 Hz. Timestamps always take priority.
Browser support for the supplied HEVC/HDR MOV files varies; a compatible
MP4/H.264 copy may be needed. Browser decoding and display refresh limit frame
presentation; this viewer does not establish hardware synchronization.

### Stitched watch recording

The two supplied sessions have increasing watch timestamps and sequence numbers,
consistent with the same watch boot. They are stitched using the original `t_us`
clock, without resetting either session's timestamp or closing the gap:

| Segment | Combined start | Combined end | Rows |
| --- | --- | --- | --- |
| watch-3e23360e5c8d89d3 | 0.000000 s | 67.986941 s | 68,435 |
| No recording | 67.986941 s | 91.377595 s | 0 |
| watch-8017f15ecd91ee46 | 91.377595 s | 107.111673 s | 15,846 |

Outputs are `recordings/stitched/watch-combined.csv` and
`recordings/stitched/watch-combined-filtered.csv`, with JSON reports and a filtered
six-axis diagnostic plot. The combined CSV has 84,281 rows, original sequence
numbers and timestamps, plus `session_id` and `t_session_s` provenance columns.
The raw source readings are unchanged. The filtered copy also retains `raw_`
backup axes. No samples are fabricated in the 23.390654 s inter-session gap.

To reproduce in a new output path:

```sh
python3 scripts/stitch_imu.py \
  samples/watch-3e23360e5c8d89d3/watch-3e23360e5c8d89d3.csv \
  samples/watch-8017f15ecd91ee46/watch-8017f15ecd91ee46.csv \
  --output recordings/review/watch-combined.csv
.tools/imu-analysis/bin/python scripts/filter_imu.py \
  recordings/review/watch-combined.csv --output-dir recordings/review --plot
```

The stitcher sorts session blocks by their original watch timestamps and rejects
non-monotonic timestamps, overlapping sessions, header mismatches, sequence resets
and existing outputs. It assumes a common watch boot; timestamps alone cannot
prove device identity across arbitrary imported recordings. Session wall-clock
metadata is not used to rebase the acquisition clock.

### Filtering and drift

Analysis tools are isolated in `.tools/imu-analysis`. Install/verify pinned NumPy,
SciPy, Matplotlib and the local FFmpeg decoder with:

```sh
sh scripts/setup-analysis.sh
```

Tools and caches stay in the repository. Original per-session filtered copies are
also available in `recordings/filtered/`.

The default is a 40 Hz fourth-order Butterworth low-pass filter, using SciPy's
[numerically stable forward/backward SOS filtering](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.sosfiltfilt.html)
to avoid net phase delay. This is an adjustable starting cutoff, not a validated
impact bandwidth. The pipeline estimates local rate from median timestamp
spacing, splits at gaps greater than `max(20 ms, five sample periods)`,
uniformizes each continuous segment, filters all six axes and maps results back
to the original rows/timestamps. It never bridges detected gaps. Short segments
are retained unfiltered and reported. Filter edge effects, clipping and missing
acquisitions cannot be recovered. JSON reports include rates, gaps, clipping,
source quality metadata where available and possible stationary intervals.

The supplied recordings have about 1792 Hz local timestamp spacing but about
1007 recorded rows/s per session because of gaps. The combined average is lower
because it includes the inter-session pause. The viewer displays local rate and
overall recorded throughput separately rather than treating either as a guaranteed
physical sensor update rate.

Gyroscope bias correction requires a **verified stationary interval** in seconds
from the first IMU row. For example, only if seconds 1–2 have been verified still:

```sh
.tools/imu-analysis/bin/python scripts/filter_imu.py \
  recordings/stitched/watch-combined.csv --stationary 1 2 \
  --output-dir recordings/bias-calibrated --plot
```

This checks for obvious motion and subtracts mean stationary gyro bias before
filtering. A stillness check cannot distinguish all slow rotation from bias. The
default generated copies have no bias subtraction because no calibration interval
has been verified. Acceleration retains gravity. Arbitrary single-pose data cannot
calibrate all accelerometer offsets, and six-axis IMU alone cannot guarantee
drift-free yaw, velocity or position.

Video clock fitting uses `video seconds = sensor seconds × scale + offset`.
Anchor matching must use the same physical events; spread anchors apart for a
useful estimate. Fits outside ±10% are rejected. This corrects constant relative
clock rate, not gyro bias, missing time or nonlinear changes.

### Video review and checks

`recordings/video-review/review.md` records the visual review and measured IMU
peaks. Local contact sheets and frame-difference traces can be regenerated with:

```sh
.tools/imu-analysis/bin/python scripts/review_videos.py
```

Frame differences include camera motion and lighting changes, so they are not an
automatic wrist-to-sensor alignment. The review uses sampled decoded frames and
higher-density excerpts; it is not a claim to have observed every source frame.

Run checks with:

```sh
.tools/imu-analysis/bin/python -m unittest discover -s tests -v
node tests/test-sync.cjs
```

## Detailed signal lab and frame timestamp audit

`imu-explorer.html` is now the detailed signal lab. The original standalone
explorer and its embedded historical recording are preserved as
`imu-explorer-legacy.html`.

Start `python3 scripts/serve_sync.py`, then open
[Signal lab](http://127.0.0.1:8000/imu-explorer.html) and click **Load stitched
recording**. The page includes six separate axes, two magnitudes, logarithmic
inter-row timing, raw/processed overlay, a draggable overview window,
Ctrl/Cmd-wheel zoom, Shift-drag pan, sample stepping, timestamped row inspection,
raw selection statistics, largest-gap navigation and motion candidates. Near-limit
flags remain attached to raw readings. Empty time has no invented sensor values.

**Apply filter** calls a local-only SciPy process through `/api/filter`. Change the
cutoff to compare filtering on all six raw axes. Optional gyro bias subtraction
requires explicitly marking a selected interval as verified stationary and passes
stillness checks. Results are held in memory; **Export window CSV** writes the
current processed axes plus original raw axes, timestamps, sequence and session
provenance. Original files are never changed. Reset restores the processed axes
originally loaded from the file. This endpoint requires the project-local analysis
environment (`sh scripts/setup-analysis.sh`); the page otherwise supports raw and
preprocessed CSV inspection without a Python backend.

Click **Load audit** to inspect exact stored video PTS/DTS, duration, presentation
index, decoding index, and the difference from `index / 120`. The video-sync page
also uses audited PTS for frame stepping when the matching frame table is present,
falling back to configured fps for unaudited files.

Regenerate the audit, including a full decoder frame-count check:

```sh
.tools/imu-analysis/bin/python scripts/audit_timestamps.py --verify-decode
```

Outputs are in `recordings/timing-audit/`: one CSV/JSON timestamp table per video,
an audit JSON and a readable `audit.md`. The actual frame counts are 1,567, 6,609
and 751. Video time base is 1/2400 s, and stored PTS intervals vary from 19 to 21
ticks (7.916667 to 8.750000 ms across the files). Packet timestamps are extracted
with stream copy and sorted by presentation order; no nominal-fps approximation
is used to build the tables. Every decoded frame count matched its packet table.

PTS is verified presentation timing. Physical exposure-start UTC is **not**
established: Apple timed metadata tracks exist but were not decoded, and creation
time alone is not proof of a per-frame UTC exposure time. The updated watch source confirms FIFO-reconstructed/host-anchored timestamps.
`startedUtc` is the whole-second recording-start value from the archive header
(or import time if the clock was unset), not the first sample time. The provided
CSV/session exports omit `bootStartUs`, so precise UTC mapping remains unverified. Assuming `startedUtc` equals first-sample UTC yields
boot-time estimates differing by 622.405 ms, compatible with whole-second metadata
but insufficient to verify frame-level synchronization or clock drift.

Additional checks: `node tests/test-imu-analysis.cjs` validates provenance, gaps,
raw/processed separation, clipping, window statistics and CSV roundtrips.

## Side-by-side review and event markers

The video sync studio (`index.html`) now keeps one reviewed video beside the live
six-axis values and acceleration/gyro plots. The camera column stays visible while
you scroll the plots and marker list on wider screens. Use **Reviewed camera** to
choose a clip, or keep **Auto · active clip** to follow the current timeline.

1. Load the stitched watch data and sample videos. Apply the rough starting
   placement only if you want to reset camera alignment; refine before labelling.
2. Seek or step to a visible event. Choose a category (racket swing, not a swing,
   uncertain, sync reference), label and optional notes.
3. Click **+ Marker · M** in the transport, **Add marker** in the form, or press
   **M**. Adding a marker pauses playback. Shortcuts are ignored while entering
   text/numbers; M also works after scrubbing the range slider. Space plays/pauses
   and arrow keys step a source frame when a text field is not being edited.
4. Video anchoring is on by default. It captures the browser's presented frame
   time when available, normalized to the audited source PTS for sample videos.
   Other videos use their reported presentation time/playhead. The export records
   which timing source was used. Video markers retain their source frame/time and
   move on the IMU timeline if that camera's offset or clock scale is changed.
5. Turn off the video-anchor checkbox to label an IMU time instead, including a
   recording gap. IMU anchors remain fixed when camera alignment changes.
6. Click a list entry or timeline marker to jump to it. Edit changes the label,
   category and notes while keeping the original event anchor. **Move to cursor**
   explicitly replaces that anchor. Deletion has an Undo action.

Markers appear on both sensor plots and on a dedicated alignment-track lane.
They are saved with camera alignment in browser local storage, keyed to the loaded
recording's name and timestamp/row identity. Reopen the same recording and videos
to restore them. Uploaded videos are matched by filename, size and modification
time; actual video/IMU file contents are not stored in local storage. Storage
failure is reported in the marker panel, and JSON export remains available.

**Export labels CSV** gives one event per row: category, label, shared-watch time,
nearest original IMU timestamp/row/sequence/source session, six raw axes, source
video/time/frame, timing source, current camera offset/scale and notes. Gaps and
out-of-recording events have empty IMU fields, never invented readings. Marker
exports are annotations, not automatically verified ground truth.

**Export markers JSON** is a reloadable backup containing source event anchors and
camera alignment. Import requires the same recording identity, merges by marker
ID and restores included camera alignment. CSV is intended for analysis; JSON is
for restoring the review session. **Export alignment JSON** also includes markers
in its version-3 session records.

Markers do not confirm accurate IMU magnitudes or correct video alignment. Label
only events you have visually checked, retain uncertain examples as uncertain,
and include non-swing movements when testing a counting algorithm.

Run `node tests/test-markers.cjs` for anchor transformation, provenance, gap,
validation and export checks. Browser QA also covers persistence, editing, undo,
CSV download, JSON import and keyboard marking.

## Included media and handoff

Raw session CSVs, metadata, stitched/filtered CSV derivatives, timing tables and
text review reports are included. Video originals, generated plots/screenshots
and NumPy data bundles remain local and are ignored by Git. Git LFS is not used.
Toolchains, caches, environments, backups, signing material and OS metadata
remain excluded.

Keep local videos in `samples/videos/` to use the sample library, or select them
through the Videos/folder controls. Fresh clones contain CSVs but no video
originals. No media download or LFS setup is required. `WORK_COMPLETED.md`
describes delivered features, verification, alignment instructions and remaining
measurement limitations. Files in `recordings/marker-qa/` are temporary test
fixtures and must not be treated as labelled swing ground truth.

## New session library and automatic clock placement

Run `python3 scripts/serve_sync.py` and open http://127.0.0.1:8000/. Click
**Open local sample library** for all 16 raw sessions and any locally available videos (currently nine). It selects
the longest overlapping new session. The transport dropdown switches recordings;
only the selected CSV is parsed, in a background worker. The library collapses
while reviewing. Expand **Browse sessions and inspect alignment clocks** to see
clock sources, overlap, clipping/outcome metadata and timezone settings.

For your own files choose **IMU CSVs** (include matching `name.csv.json` metadata),
then **Videos**, or choose their shared **Recording folder**. Standard recordings
use `t_us,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps`; single nonstandard CSVs retain manual
column mapping. Files remain local. Large video inspection skips media payloads
and reads bounded MP4/MOV metadata and presentation timing tables.

Automatic placement compares first-sample CSV UTC with estimated video start UTC:
`video_seconds = watch_seconds + (csv_start_utc - video_start_utc)`.
Unix µs CSVs take priority; boot-relative CSVs need a UTC anchor or a visibly
flagged recording-start proxy. A copy/modification timestamp is never used.
The six new Android MP4 creation tags match **recording end** within 1.8 seconds.
The detector uses their `VIDYYYYMMDDHHMMSS` filename start with explicit timezone
(default `+05:30`), flags uncertainty, and refuses conflicting clocks. Override the
clock convention only when you know its meaning. Timestamp matching sets scale 1;
it cannot measure drift. Use two shared events to fit drift manually.

Manual offsets, inclusion choices and fitted drift are preserved per recording.
**Reapply timestamp alignment** explicitly replaces them with clock estimates.
Unmatched clips are excluded and do not stretch the visible timeline across days.
Drag tracks or use **Preview / Align here** to refine one clearly visible event.
Metadata placement is not proof of physical sensor accuracy or frame-level sync.
See `recordings/new-session-review/report.md` for the 14-session analysis and
`node tests/test-import-clock.cjs` for clock/precision/frame-table verification.

## Interactive charts and replay zones

In Sync Studio, drag either sensor chart to select an interval; click to seek.
Both charts share their zoom, hover crosshair and highlighted zone. The overview
shows the full recording: dragging it selects a zone and opens that interval.
Use **＋ Zoom**, **− Zoom**, **Zoom to zone**, or **Fit recording**. Ctrl/Command +
wheel zooms around the pointer; Shift + drag pans. Focus a chart and use +/− to
zoom or Shift + arrow to pan. Double-click fits the full recording.

Enter exact **Zone from / to** times and click **Set zone** if preferred. Click
**Replay zone** to play sensor data and aligned video within those boundaries.
**Loop** repeats it; turn Loop off to stop at the end. Pause uses the main Play
button or Space. Seeking outside the zone returns to normal timeline playback.
**Save zone** stores a named interval for that recording in browser local storage;
select its chip to recall/zoom, ▶ to replay, or × to remove it. These intervals
are separate from event markers. Alignment JSON exports include saved replay
zones for the loaded recordings. Keep an export as a backup of browser storage.

The detailed IMU analyser also supports drag selection on any plot and **Replay
window / Loop window**. Its window CSV export uses the selected range. Ordinary
Play continues on the recording timeline; Replay limits playback to the chosen
interval.

Traces retain first/last samples and timestamp-ordered extrema per pixel, keeping
real peaks and visible gaps. Chart backgrounds are cached between view changes,
so playback redraws cursor/selection overlays instead of scanning the whole CSV
for every frame. Smooth drawing does not modify raw readings or apply a signal
filter. Low-pass filtering remains an explicit analyser action.

Run `node tests/test-graph-core.cjs` for zoom, bounded panning, replay boundaries,
peak preservation, gap disconnection and unchanged-data checks.
