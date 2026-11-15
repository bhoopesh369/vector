# Local video and IMU review

Reviewed decoded contact sheets throughout all three source videos at 0.5 s
(short clips) or 2 s (long clip) intervals, plus denser excerpts at 0–10 s and
34–40 s in IMG_3303 and 2–6.2 s in IMG_3304. Video traces were decoded at 30 fps;
original streams report approximately 119.95 fps. This is a sampled visual review,
not inspection of every source frame. All videos are portrait-rotated HEVC/HDR MOV.

| Video | Duration | Observation | Likely data relationship |
| --- | --- | --- | --- |
| IMG_3302.MOV | 13.06 s | Racket practice, with swings near the latter part of the clip | File creation time precedes the first watch session metadata by 31 s; no confirmed matching sensor interval |
| IMG_3303.MOV | 55.10 s | Three early racket swings, later racket motion; substantial camera movement in the middle | First watch session is a plausible match; early swing spacing resembles IMU bursts |
| IMG_3304.MOV | 6.26 s | Punching movements toward the camera | Second watch session is a plausible match |

Creation times establish a useful coarse ordering, but do not prove an acquisition
start time or a precise offset. Suggested UI placement is deliberately rough:
IMG_3303 starts at combined time 0.9 s; IMG_3304 at 95.377595 s (4 s into session
two); IMG_3302 is placed at -32.1 s and excluded by default. No clock-rate fit has
been inferred from these sparse observations. Refine the offsets against the same
visible motion events and confirm them at another point before trusting sync.

## Raw sensor findings

| Session | Rows | Duration | Max recorded acceleration magnitude | Max recorded gyro magnitude | Clipped rows |
| --- | --- | --- | --- | --- | --- |
| 3e23360e5c8d89d3 | 68,435 | 67.986941 s | 20.541 g | 1773.620 deg/s | 396 (0.579%) |
| 8017f15ecd91ee46 | 15,846 | 15.734078 s | 20.180 g | 1708.408 deg/s | 277 (1.748%) |

Magnitudes can exceed the per-axis limits because they combine three axes. Values
at +/-16 g or +/-1024 deg/s indicate per-axis clipping; the true peaks cannot be
reconstructed by filtering. These are recorded maxima, not validated physical
swing or impact maxima. No smash/contact classifier was used.

Prominent raw gyro magnitude peaks after grouping peaks within 0.5 s:

- Session one: 2.174, 4.153, 6.377, 33.828 and 37.988 s relative to its first row.
- Session two: 6.332, 8.541 and 9.985 s (strong peaks), corresponding to approximately
  97.710, 99.919 and 101.363 s on the combined watch timeline.

Both sessions have median local timestamp spacing near 1792 Hz, but about 1007
recorded rows per wall-timeline second because of gaps. The first session metadata
reports one sensor read error and acquisition loss. Filtering cannot restore the
unobserved data. Full-image frame differences are contaminated by lighting flicker
and camera movement, so they were not used to claim an automatic correlation.

## Stitching

The original watch timestamps and sequence numbers increase across the sessions,
consistent with one watch boot. No timestamps were shifted. The second session
begins at 91.377595 s, after a 23.390654 s gap from the first session's last sample.
The combined duration is 107.111673 s with 84,281 rows. Provenance is retained in
session_id; t_session_s is relative to the first original timestamp.
