# New session review — 15 November 2026

Inspected all 14 new CSVs (1,941,960 samples) and metadata for all six new MP4s. Original files were not rewritten. No swings have been manually labeled or sensor accuracy established by these checks.

## Clock alignment

New Unix-microsecond CSV values use the declared phone BLE receive estimate. They are calendar anchors, with acquisition/transport latency remaining. Older boot-relative CSVs require an archive UTC anchor; recording-start metadata is only a low-confidence proxy. File copy modification time is never used.

All six Android video creation tags match the filename start plus clip duration to within 1.8 seconds. Treating those tags as start time would misalign clips by their entire duration. The automatic detector therefore uses VID filename start with explicit +05:30 timezone and flags its second-scale uncertainty. A conflicting clock leaves the clip unaligned.

For the 07:42:29.338522 UTC CSV, VID20261115131230.mp4 starts at estimated 07:42:30 UTC: video_seconds = watch_seconds − 0.661478. Scale stays 1 until two manually verified shared events justify a drift fit. Metadata does not establish physical exposure start.

## Acquisition checks

| CSV | Rows | Saved rows/s | Median interval µs | Largest gap ms | Metadata clipped | Outcome |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| badminton-fullrate-2026-11-15T06-27-50.049168Z.csv | 17,029 | 1175.67 | 558.0 | 20.359 | 0 | Stopped by user |
| badminton-fullrate-2026-11-15T07-02-10.465701Z.csv | 4,997 | 1179.35 | 558.0 | 20.322 | 0 | Stopped by user |
| badminton-fullrate-2026-11-15T07-02-18.538741Z.csv | 364,969 | 1174.15 | 558.0 | 20.373 | 1989 | Stopped by user |
| badminton-fullrate-2026-11-15T07-07-53.193843Z.csv | 67,787 | 1174.48 | 558.0 | 20.378 | 42 | Disconnected by user |
| badminton-fullrate-2026-11-15T07-15-03.256781Z.csv | 35,127 | 1174.26 | 558.0 | 20.759 | 107 | Interrupted: full-rate Bluetooth queue overflow; reconnect |
| badminton-fullrate-2026-11-15T07-16-31.702627Z.csv | 51,429 | 1174.18 | 558.0 | 20.374 | 110 | Interrupted: full-rate Bluetooth queue overflow; reconnect |
| badminton-fullrate-2026-11-15T07-42-29.347660Z.csv | 419,670 | 1174.17 | 558.0 | 20.752 | 1445 | Stopped by user |
| badminton-fullrate-2026-11-15T07-50-05.732992Z.csv | 232,100 | 1174.22 | 558.0 | 20.866 | 1772 | Disconnected by user |
| badminton-fullrate-2026-11-15T07-54-35.976480Z.csv | 339,057 | 1174.15 | 558.0 | 20.763 | 2790 | Stopped by user |
| other-fullrate-2026-11-14T23-17-05.579358Z.csv | 36,701 | 1175.01 | 558.0 | 20.325 | 0 | Stopped by user |
| other-fullrate-2026-11-14T23-27-43.534546Z.csv | 37,223 | 1174.95 | 558.0 | 20.331 | 0 | Stopped by user |
| running-fullrate-2026-11-15T07-30-14.120006Z.csv | 99,419 | 1174.43 | 558.0 | 20.360 | 0 | Stopped by user |
| running-fullrate-2026-11-15T07-32-22.260284Z.csv | 107,641 | 1174.43 | 558.0 | 20.357 | 0 | Stopped by user |
| running-fullrate-2026-11-15T07-37-19.437255Z.csv | 128,811 | 1174.38 | 558.0 | 20.382 | 1101 | Stopped by user |

All timestamps increase strictly. The median within-batch interval is about 558 µs, while the saved average is about 1174 rows/s in the new full-rate recordings. Neither value alone establishes continuous acquisition. Full numeric axis ranges and gap counts are in imu-quality.json. Clipped samples remain raw; a filter cannot restore values beyond the hardware range. Two sessions explicitly report Bluetooth queue-overflow interruption.

## Frame checks

The bounded browser parser reproduces all 8,927 previously FFmpeg-audited MOV presentation timestamps to <10 ns. The new short MP4 has the same 362 frames as FFmpeg packet extraction. ISO edit-list mapping and FFmpeg normalization differ by a constant 1.533 ms for that MP4; do not interpret the calendar estimate as sub-frame verification. The 07:42 video averages 49.26 fps and is variable rate, so index / 120 is incorrect. The viewer uses parsed presentation timestamps, and displayed-frame callbacks for marker anchors when available. Full decoding of all six new MP4s was not performed.

## Use

Run `python3 scripts/serve_sync.py`, open http://127.0.0.1:8000, and click Open local sample library. The longest matching recording loads first. Select any CSV from the transport dropdown. For new files choose CSVs plus their matching metadata JSON and videos, or select their common folder. Check the alignment evidence, seek to a clear motion event and drag the video track or use Preview / Align here. Saved manual adjustments take priority over later automatic estimates.
