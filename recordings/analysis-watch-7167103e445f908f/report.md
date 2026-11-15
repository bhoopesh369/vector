# IMU recording review

Source: `/Users/sriman/Downloads/watch-7167103e445f908f.csv`. Original left unchanged.

The recording contains clearly separable movement bursts and is useful for exploratory swing detection. It does not support a validated smash count or accurate peak angular velocity. Saturation and irregular acquisition timing are the main observed limitations. Pure sensor noise cannot be isolated without known stationary data.

## Data integrity and measurement limits

| Measure | Result |
| --- | ---: |
| Duration, first to last timestamp | 43.412923 s |
| Rows | 43,171 |
| Missing/nonfinite numeric values | 0 |
| Non-increasing timestamps | 0 |
| Sequence discontinuities | 0 |
| Rows flagged clipped | 2,192 (5.08%) |
| Rows with acceleration axis at or beyond 15.8 g | 386 (0.89%) |
| Rows with gyro axis at or beyond 1010 degrees/s | 2,184 (5.06%) |
| Median row interval | 0.558 ms |
| Maximum row interval | 102.958 ms |
| Intervals exceeding 20 ms | 360 |
| Intervals exceeding 50 ms | 171 |

Accelerometer axes reach ±16 g; gyro axes reach ±1024 degrees/s. These are per-axis limits: vector magnitudes can exceed these values without implying a wider per-axis sensor range. Clipped measurements cannot be restored by smoothing. Row clipping percentages are not time-weighted percentages because acquisition timing is irregular.

Average row throughput is 994.4 rows/s. It must not be interpreted as verified hardware sample frequency. Repository watch firmware on `origin/codex/sports-app-redesign` configures nominal six-axis sampling around 112.1 Hz and timestamps reads with `esp_timer_get_time()`. This differs substantially from the CSV timing; the exact firmware running during capture is unverified. Check actual ODR registers, data-ready handling and FIFO before using spectral features or integration. Continuous software sequence numbers cannot establish that no hardware updates were missed.

1,354 of 4,342 10 ms bins contain no recorded row (31.2%). This describes observation coverage, not a known hardware loss rate. Only one adjacent pair repeats all six axes exactly, so this is not simply a file full of identical repeated rows.

## Noise versus movement

The quietest eligible one-second window was 0–1 s. Windows were compared on gyro variance after averaging into observed 10 ms bins; each required at least 70 observed bins. No missing bins were interpolated for this calculation.

| Axis | Raw standard deviation in 0–1 s |
| --- | ---: |
| ax | 0.0488 g |
| ay | 0.0373 g |
| az | 0.0203 g |
| gx | 4.02 degrees/s |
| gy | 8.97 degrees/s |
| gz | 3.02 degrees/s |

These values combine sensor noise, tremor and movement. The file does not establish a motionless calibration interval, so a pure noise floor, gyro bias or signal-to-noise ratio is not justified. Large repeated bursts are clearly distinct from this quiet-period variability.

## Exploratory swing count

The baseline detector finds **17 unclassified swing candidates** at:

7.55, 10.61, 16.95, 18.48, 20.52, 21.62, 23.96, 24.67, 25.79, 26.57, 27.32, 29.16, 30.56, 33.86, 35.47, 37.28 and 38.20 seconds from the first row.

Method: calculate acceleration and gyro vector magnitudes; interpolate their observed values onto a 100 Hz grid; smooth with Gaussian sigma 20 ms; select acceleration peaks at least 4 g high with at least 2 g prominence, at least 0.6 s apart, and a gyro magnitude of at least 400 degrees/s within ±0.15 s. Acceleration includes gravity. Interpolation is for exploration only and cannot recover unobserved peaks.

Raising peak height to 5 g retains **16 strong candidates**, excluding 26.57 s. Across tested heights 3–6 g, prominences 1.5–3 g and separations 0.4–0.8 s, counts range from **15 to 22**. This is parameter sensitivity, not a confidence interval or validated error bound. Weak swings may be omitted; preparation or follow-through can create extra peaks. Timing gaps can change detection.

All 17 baseline candidates have clipped rows within ±0.25 s. Peaks in `candidate-swings.csv` are measured values or smoothed values as labeled, not corrected physical peak values.

**Verified smash count: unknown.** No labeled strokes, trained device-matched classifier or synchronized video were supplied. Overhead clears, smashes and air swings can resemble each other. Do not label all high-amplitude swings as smashes or infer racket-head/shuttle speed from these signals.

## Recommended next recording

1. Resolve the ODR/timestamp mismatch and use FIFO/sample timestamps where supported; log actual configuration with each session.
2. Increase gyro full scale to the highest supported setting and verify residual clipping. Acceleration also saturates, so evaluate its range or sensor choice. Changes need fresh validation.
3. Include 5–10 seconds with the device stationary on a table, then controlled labeled swings with pauses, plus walking/handling examples.
4. Keep placement and strap tightness consistent and record synchronized video or manually marked stroke labels.
5. Validate event count against labels before classifying stroke type. Evaluate on separate recordings and, for general use, held-out players.

## Badminton references

- [mr-easy/Badminton-Stroke-Classification](https://github.com/mr-easy/Badminton-Stroke-Classification): wrist accelerometer and gyro classification with an explanatory notebook. Closest basic wrist-data starting point.
- [taizhouchen/BadminSense_Dataset](https://github.com/taizhouchen/BadminSense_Dataset): smartwatch data at 100 Hz, 848 labeled strokes, IMU/audio/video. The repository states CC BY-NC-ND 4.0 restrictions; review before reuse.
- [PhusNguyen/Realtime-IMU-Badminton-Stroke-Recognition](https://github.com/PhusNguyen/Realtime-IMU-Badminton-Stroke-Recognition): segmentation and classifiers, 9,784 labeled strokes across eight classes, including smash with/without shuttle contact. Racket-mounted data requires wrist-specific validation; reported test accuracy is not expected accuracy for this recording. README reports clear/smash confusion.
- [PhusNguyen/IMU-Badminton-Data-Collection-System](https://github.com/PhusNguyen/IMU-Badminton-Data-Collection-System): related ESP32/racket-mounted six-axis collection system.
- [Yixuan-Wu39/bandminton](https://github.com/Yixuan-Wu39/bandminton): accelerometer-only wearable event gating and four motion-structure classes; useful architecture reference rather than a ready smash classifier.

Outputs: `overview.png`, `candidate-swings.csv`, and `quality.json`. Analysis implementation is in the repository-local ignored `.cache/imu-analysis/analyze.py`; dependencies are isolated in `.tools/imu-analysis`.
