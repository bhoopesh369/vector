# Experimental badminton review mode

This mode proposes wrist-motion events for video review. It does not establish
shuttle contact from an acceleration kink alone. Choose Sports analysis →
Badminton after loading a CSV/video session. Estimated hits include pending and
uncertain candidates; Confirmed contacts counts only the user's confirmations.
Reject empty swings and unrelated movements. Add visually verified missed hits at
the playhead. These decisions are separate from existing event markers.

## Research checked before implementation

- [Steels et al., Sensors (2020)](https://www.mdpi.com/1424-8220/20/17/4685)
  compares sensor placements. Impact spikes were clearest at the racket grip;
  wrist measurements had less impact detail. A wrist Y/Z kink alone therefore
  does not establish contact.
- [BadminSense, CHI 2026](https://arxiv.org/html/2603.21825v1),
  [DOI](https://doi.org/10.1145/3772318.3790998), uses gyro-based candidate windows
  followed by audio verification to reduce empty-swing false positives. Its
  thresholds and trained models are specific to its setup. They are not copied
  into this detector, and its reported accuracy does not apply here.

The implementation below is an inspectable heuristic baseline. It has no trained
contact classifier, acoustic verification or calibrated force/speed model.

## Current algorithm

1. Read original six-axis columns when raw backup columns are available;
   otherwise use the CSV axes as supplied. Full-resolution input is retained.
2. Group samples into 5 ms time bins. Keep vector means for timing features and
   raw sample maxima, squared sums, counts, recorded clipping flags and intervals
   for measured feature summaries. Empty bins are not filled.
3. Estimate acceleration-vector change after an approximately 8 ms exponential
   smoothing step. Reset derivatives when bin timing is discontinuous or a raw
   interval exceeds 12 ms, avoiding derivative spikes across missing data.
4. Find local acceleration-change peaks above the greater of the user floor and
   a robust noise floor (median + 4 × 1.4826 × MAD).
5. Require both raw acceleration magnitude and raw gyro magnitude in the ±160 ms
   context. Defaults are 3 g and 180 °/s, with a 60 g/s minimum change threshold.
6. Apply time-based non-maximum suppression, initially 450 ms, to avoid counting
   multiple impulses from one movement. This spacing is adjustable. Fast successive
   shots may merge; one complex swing can still yield multiple events.
7. Return all automatic events as pending. Event times are acceleration-change
   proposals, not verified exposure-synchronized impact timestamps.

Analysis runs in a worker. Input copies are transferred; the source arrays,
manual camera alignment, event markers and raw CSV files are preserved. Changing
settings requires Reanalyse. The chosen interval and effective threshold are
reported with the results. Selecting/replaying an event changes the chart/video
view without silently recomputing the analysis.

## Weak, medium and strong

These are relative wrist-motion bands. The score is the gyro magnitude RMS of
measured samples in the event context. Clean included events set the lower and
upper tertile boundaries. No category represents shuttle speed, racket speed,
impact force, stroke type or technique quality. Rejected events do not set the
bands. Changing the candidate set or review exclusions can change the boundaries.
Identical scores remain medium rather than being split artificially.

Events with recorded clipping or near-limit axes (15.8 g or 1010 °/s) remain
unrated: saturation prevents recovering the true peak. With fewer than six clean
included events, all remaining events are unrated. Gyro vector magnitude can
exceed the per-axis range without every axis clipping.

## Metrics and review

The mode shows estimated hits, user-confirmed contacts, review backlog, included
events per minute of the analysed interval (only for intervals of at least 10 s),
median event spacing, measured wrist
angular-speed magnitude peaks and relative intensity totals. It does not infer
rallies, winning shots, scores, calorie expenditure, heart rate or shuttle speed.

Click an event to seek and zoom around it; ▶ Review replays that interval with the
aligned video and existing loop controls. Manual additions require a nearby
recorded sample and cannot duplicate an event within 120 ms. Confirm/reject/
uncertain decisions and manual times persist per recording in browser storage.
Reruns reuse decisions only for identical event IDs; a changed proposal needs a
new review. No existing swing marker is silently treated as confirmed contact.

CSV export includes every candidate, including rejected examples, with its source
microsecond timestamp, review, origin, relative intensity, measured features,
clipping/gap flags and detector settings. JSON also records the algorithm version,
clock provenance, scope, intensity boundaries and video alignment. Browser-local
reviews are not shared automatically; export them as a backup.

## Checks and current evidence

Synthetic checks cover isolated impulses, rest, rotation-only and acceleration-only
negatives, mounting-axis transformations, different sample rates, multi-peak
suppression, gap discontinuities, range selection, clipping, relative bands,
reviewed counts, manual events and packed-worker input. These establish software
behavior, not contact-detection accuracy.

On the supplied 419,670-row session beginning 07:42:29 UTC, defaults propose 68
candidates, 21 near a measurement limit. A proposal occurs at 254.532 s near the
user-highlighted plot kink at 254.526 s. None of these counts was established by
continuous video annotation. Exact agreement with the displayed cursor is not an
impact-timing accuracy measurement; clock alignment also remains approximate.

Before using counts as validated training results, independently label complete
video intervals with contacts, empty swings and other wrist movements. Include
soft shots and missed contacts. Tune on one subset, then measure precision,
recall, duplicate counts and timing error on held-out sessions/players. Candidate
confirmation alone cannot estimate recall because undetected hits are absent.

```sh
node tests/test-sports-core.cjs
node tests/test-sports-worker.cjs
```

Export/storage contracts are additionally checked with
`node tests/test-sports-export.cjs`: quoted recording names, original UTC
microseconds, provisional status, algorithm/clock provenance and review restoration.
