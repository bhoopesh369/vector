#!/usr/bin/env python3
"""Offline timestamp-aware six-axis filtering; never overwrites source recordings."""
import argparse
import csv
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault('MPLCONFIGDIR', str(ROOT / '.cache/matplotlib'))
import numpy as np
from scipy.signal import butter, sosfiltfilt

AXES = ['ax_g', 'ay_g', 'az_g', 'gx_dps', 'gy_dps', 'gz_dps']


def process(times, values, cutoff=40., stationary=None, gap_seconds=.02):
    times, values = np.asarray(times, dtype=float), np.asarray(values, dtype=float)
    if len(times) < 20 or values.shape != (len(times), 6):
        raise ValueError('Need at least 20 rows with six axes.')
    if not np.isfinite(times).all() or not np.isfinite(values).all() or np.any(np.diff(times) <= 0):
        raise ValueError('Readings must be finite and timestamps strictly increasing.')
    if not np.isfinite(cutoff) or cutoff <= 0 or not np.isfinite(gap_seconds) or gap_seconds <= 0:
        raise ValueError('Cutoff and gap threshold must be finite and positive.')
    dt = np.diff(times)
    hz = 1 / np.median(dt)
    if cutoff >= hz / 2:
        raise ValueError(f'Cutoff must be below Nyquist ({hz / 2:.2f} Hz).')
    # Keep both long time gaps and unusually large local sample intervals separate.
    breaks = np.flatnonzero(dt > max(gap_seconds, 5 / hz)) + 1
    segments = np.split(np.arange(len(times)), breaks)
    bias = np.zeros(3)
    calibration = None
    if stationary is not None:
        start, end = stationary
        if not np.isfinite([start, end]).all() or end - start < .5 or start < times[0] or end > times[-1]:
            raise ValueError('Stationary interval must be within this session and at least 0.5 s.')
        mask = (times >= start) & (times <= end)
        v, ts = values[mask], times[mask]
        if len(v) < 20 or np.any(np.diff(ts) > .25):
            raise ValueError('Stationary calibration is too sparse or crosses a recording gap.')
        if (np.max(np.std(v[:, :3], axis=0)) > .025 or
                np.max(np.std(v[:, 3:], axis=0)) > 1.5 or
                abs(np.mean(np.linalg.norm(v[:, :3], axis=1)) - 1) > .15 or
                np.linalg.norm(np.mean(v[:, 3:], axis=0)) > 20):
            raise ValueError('Calibration interval fails stillness checks; choose a verified stationary interval.')
        bias = np.mean(v[:, 3:], axis=0)
        calibration = {'start_s': start, 'end_s': end, 'rows': len(v)}
    corrected = values.copy()
    corrected[:, 3:] -= bias
    filtered = corrected.copy()
    skipped = []
    for indices in segments:
        if len(indices) < 20:
            skipped.append([int(indices[0]), int(indices[-1])])
            continue
        t = times[indices]
        # Uniformize within each continuous segment for digital filter design;
        # interpolate back to the original timestamps for sync and row identity.
        n = max(20, round((t[-1] - t[0]) * hz) + 1)
        grid = np.linspace(t[0], t[-1], n)
        rate = (n - 1) / (t[-1] - t[0])
        if cutoff >= rate / 2:
            raise ValueError('Segment sampling rate is too low for this cutoff.')
        sos = butter(4, cutoff, fs=rate, output='sos')
        uniform = np.column_stack([np.interp(grid, t, corrected[indices, j]) for j in range(6)])
        smooth = sosfiltfilt(sos, uniform, axis=0)
        filtered[indices] = np.column_stack([np.interp(t, grid, smooth[:, j]) for j in range(6)])
    report = {'rows': len(times), 'duration_s': float(times[-1] - times[0]),
              'average_recorded_hz': float((len(times) - 1) / (times[-1] - times[0])),
              'median_sample_hz': float(hz), 'cutoff_hz': cutoff, 'filter': 'Butterworth order 4, forward/backward SOS',
              'gap_count': len(breaks), 'max_interval_s': float(dt.max()),
              'short_unfiltered_segments_row_ranges': skipped, 'gyro_bias_dps': bias.tolist(),
              'stationary_calibration': calibration,
              'limitations': ['No accelerometer bias calibration or gravity subtraction.',
                             'No absolute heading, position, or temperature drift correction.',
                             'No interpolation across detected gaps; edge effects remain near segment boundaries.',
                             'Bias is constant and only valid if the supplied interval was stationary.']}
    return filtered, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inputs', type=Path, nargs='+')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'recordings/filtered')
    parser.add_argument('--cutoff', type=float, default=40., help='Low-pass cutoff Hz; review for your motion bandwidth.')
    parser.add_argument('--stationary', type=float, nargs=2, metavar=('START_S', 'END_S'), help='Verified still interval relative to first row; use per-session invocations when intervals differ.')
    parser.add_argument('--gap-seconds', type=float, default=.02)
    parser.add_argument('--plot', action='store_true')
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for source in args.inputs:
        target = args.output_dir / (source.stem + '-filtered.csv')
        report_file = target.with_suffix('.json')
        plot_file = target.with_suffix('.png')
        if any(p.exists() for p in [target, report_file, plot_file]) or source.resolve() == target.resolve():
            parser.error(f'Output exists; choose another output directory: {target}')
        with source.open(newline='') as handle:
            reader = csv.DictReader(handle)
            headers = reader.fieldnames
            if not headers or not {'t_us', *AXES}.issubset(headers):
                parser.error(f'{source}: expected t_us and {AXES}')
            if any('raw_' + a in headers for a in AXES):
                parser.error('This file already contains raw backup axes; select the original source CSV.')
            rows = list(reader)
        try:
            stamps = np.array([float(r['t_us']) for r in rows])
            times = (stamps - stamps[0]) / 1e6
            raw = np.array([[float(r[a]) for a in AXES] for r in rows])
            filtered, report = process(times, raw, args.cutoff, args.stationary, args.gap_seconds)
        except (ValueError, IndexError) as error:
            parser.error(f'{source}: {error}')
        report['source'] = str(source)
        session_file = source.parent / 'session.json'
        if session_file.exists():
            try:
                report['source_session_metadata'] = json.loads(session_file.read_text())
            except (OSError, ValueError):
                report['source_session_metadata_error'] = 'Could not read session.json'
        report['stationary_candidates_s'] = []
        # Candidates are suggestions only; bias is never inferred automatically.
        for start in np.arange(0, max(0., times[-1] - .75), .75):
            mask = (times >= start) & (times <= start + .75)
            v = raw[mask]
            if (len(v) >= 20 and np.max(np.diff(times[mask])) < .25 and
                    np.max(np.std(v[:, :3], axis=0)) < .025 and
                    np.max(np.std(v[:, 3:], axis=0)) < 1.5 and
                    abs(np.mean(np.linalg.norm(v[:, :3], axis=1)) - 1) < .15 and
                    np.linalg.norm(np.mean(v[:, 3:], axis=0)) < 20):
                report['stationary_candidates_s'].append([float(start), float(start + .75)])
        report['clipped_rows'] = sum(r.get('clipped', '0') == '1' for r in rows)
        with target.open('x', newline='') as handle:
            writer = csv.DictWriter(handle, fieldnames=headers + ['raw_' + a for a in AXES])
            writer.writeheader()
            for row, original, smooth in zip(rows, raw, filtered):
                result = dict(row)
                for a, r, f in zip(AXES, original, smooth):
                    result['raw_' + a] = f'{r:.9g}'
                    result[a] = f'{f:.9g}'
                writer.writerow(result)
        with report_file.open('x') as handle:
            json.dump(report, handle, indent=2)
        if args.plot:
            import matplotlib
            matplotlib.use('Agg')
            import matplotlib.pyplot as plt
            fig, plots = plt.subplots(6, 1, figsize=(14, 12), sharex=True)
            # Each segment is drawn separately, so missing time is visibly unconnected.
            cuts = np.flatnonzero(np.diff(times) > max(args.gap_seconds, 5 / report['median_sample_hz'])) + 1
            for j, ax in enumerate(plots):
                for k, indices in enumerate(np.split(np.arange(len(times)), cuts)):
                    stride = max(1, len(indices) // 20000)
                    idx = indices[::stride]
                    ax.plot(times[idx], raw[idx, j], color='gray', alpha=.5, lw=.5, label='Raw' if k == 0 else None)
                    ax.plot(times[idx], filtered[idx, j], color='#2679c4', lw=.7, label='Filtered' if k == 0 else None)
                ax.set_ylabel(AXES[j]); ax.grid(alpha=.2)
            plots[0].legend(); plots[-1].set_xlabel('Seconds from first IMU reading')
            fig.suptitle(source.stem + ' / raw and filtered'); fig.tight_layout(); fig.savefig(plot_file, dpi=130); plt.close(fig)
        print(f'{target}: {report["rows"]} rows, {report["median_sample_hz"]:.1f} Hz, {report["gap_count"]} gaps, bias {report["gyro_bias_dps"]}')


if __name__ == '__main__':
    main()
