#!/usr/bin/env python3
"""Audit original watch clocks and extract stored video packet PTS/DTS, without re-encoding."""
import argparse
import csv
from datetime import datetime, timedelta
from fractions import Fraction
import json
from pathlib import Path
import re
import subprocess
import imageio_ffmpeg

ROOT = Path(__file__).resolve().parents[1]


def parse_framecrc(text):
    match = re.search(r'^#tb 0: (\d+)/(\d+)', text, re.M)
    if not match:
        raise ValueError('Missing video time base')
    base = Fraction(int(match[1]), int(match[2]))
    frames = []
    for line in text.splitlines():
        if not line or line.startswith('#'):
            continue
        cells = [cell.strip() for cell in line.split(',')]
        if len(cells) < 6 or cells[0] != '0':
            raise ValueError('Unexpected framecrc packet record')
        frames.append({'decode_index': len(frames), 'dts_ticks': int(cells[1]),
                       'pts_ticks': int(cells[2]), 'duration_ticks': int(cells[3]),
                       'packet_bytes': int(cells[4])})
    frames.sort(key=lambda frame: frame['pts_ticks'])
    for i, frame in enumerate(frames):
        frame.update(frame_index=i, pts_s=float(frame['pts_ticks']*base),
                     dts_s=float(frame['dts_ticks']*base), duration_s=float(frame['duration_ticks']*base))
    if not frames or any(b['pts_ticks'] <= a['pts_ticks'] for a, b in zip(frames, frames[1:])):
        raise ValueError('Missing, duplicate or non-increasing presentation timestamps')
    return base, frames


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=ROOT/'recordings/timing-audit')
    parser.add_argument('--verify-decode', action='store_true', help='Also decode every video frame and compare the frame count.')
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    exe = imageio_ffmpeg.get_ffmpeg_exe()
    audit = {'videos': [], 'watch_sessions': [], 'verification': {
        'video': 'Actual encoded-packet PTS and DTS extracted by stream copy, sorted by PTS; not frame index / nominal fps.',
        'exposure_start': 'Not established. PTS is a presentation timestamp, not proof of the physical exposure start or exact UTC capture time. Apple timed metadata tracks were not decoded.',
        'watch': 'Verified current source: QMI FIFO samples use the processor uptime clock, reconstructed inside batches at nominal 1793.6 Hz and anchored to host time. See high_rate.hpp and motion_service.cpp.',
        'utc_mapping': 'Verified source: startedUtc comes from the watch archive header startedUnix (whole Unix seconds at recording start), with Android import-time fallback when unset. This is not the first sample timestamp. CSV/session exports omit bootStartUs, and BLE clock-setting latency is unmeasured, so exact first-sample UTC is not established.'}}
    for source in sorted((ROOT/'samples/videos').glob('*.MOV')):
        packet_text = subprocess.run([exe, '-v', 'error', '-copyts', '-i', str(source), '-map', '0:v:0', '-c:v', 'copy', '-f', 'framecrc', '-'], capture_output=True, text=True, check=True).stdout
        base, frames = parse_framecrc(packet_text)
        metadata = subprocess.run([exe, '-hide_banner', '-i', str(source)], capture_output=True, text=True).stderr
        creation = re.search(r'^\s+creation_time\s+: (.+)$', metadata, re.M)
        deltas = [b['pts_ticks']-a['pts_ticks'] for a, b in zip(frames, frames[1:])]
        histogram = {str(tick): deltas.count(tick) for tick in sorted(set(deltas))}
        decoded_count = None
        if args.verify_decode:
            decoded = subprocess.run([exe, '-v', 'error', '-i', str(source), '-map', '0:v:0', '-an', '-fps_mode', 'passthrough', '-progress', 'pipe:1', '-f', 'null', '-'], capture_output=True, text=True, check=True)
            counts = re.findall(r'^frame=(\d+)', decoded.stdout, re.M)
            decoded_count = int(counts[-1]) if counts else None
            if decoded_count != len(frames):
                raise ValueError(f'Packet/decode count mismatch for {source}: {len(frames)} vs {decoded_count}')
        csv_path = args.output_dir/(source.stem+'-frames.csv')
        with csv_path.open('w', newline='') as handle:
            writer = csv.DictWriter(handle, fieldnames=list(frames[0])); writer.writeheader(); writer.writerows(frames)
        (args.output_dir/(source.stem+'-frames.json')).write_text(json.dumps({'time_base': str(base), 'frames': frames}, separators=(',', ':')))
        audit['videos'].append({'name': source.name, 'creation_time_utc': creation[1].strip() if creation else None,
            'time_base': str(base), 'frame_count': len(frames), 'decoded_frame_count': decoded_count, 'first_pts_s': frames[0]['pts_s'],
            'last_pts_s': frames[-1]['pts_s'], 'presentation_end_s': max(f['pts_s']+f['duration_s'] for f in frames),
            'interval_tick_histogram': histogram, 'min_interval_s': float(min(deltas)*base),
            'max_interval_s': float(max(deltas)*base), 'frame_table': 'recordings/timing-audit/'+csv_path.name,
            'frame_json': 'recordings/timing-audit/'+source.stem+'-frames.json'})
    boots = []
    for source in sorted((ROOT/'samples').glob('watch-*/*.csv')):
        metadata = json.loads((source.parent/'session.json').read_text())
        with source.open() as handle:
            rows = list(csv.DictReader(handle))
        start, end = int(rows[0]['t_us']), int(rows[-1]['t_us'])
        wall = datetime.fromisoformat(metadata['startedUtc'].replace('Z', '+00:00'))
        implied_boot = wall-timedelta(microseconds=start)
        boots.append(implied_boot)
        audit['watch_sessions'].append({'name': source.stem, 'rows': len(rows), 'first_t_us': start, 'last_t_us': end,
            'started_utc_metadata': metadata['startedUtc'], 'timestamp_mode': metadata['timestampMode'],
            'implied_boot_utc_if_started_equals_first_sample': implied_boot.isoformat(),
            'metadata_precision': 'whole-second recording-start time; bootStartUs not included in supplied CSV/session exports'})
    audit['implied_boot_disagreement_ms'] = (max(boots)-min(boots)).total_seconds()*1000 if boots else None
    (args.output_dir/'audit.json').write_text(json.dumps(audit, indent=2))
    lines = ['# Timestamp verification', '', '## What is verified', '',
        '- Video timing below comes from actual stored packet timestamps; packets are sorted by presentation order, not decoder order.',
        '- PTS identifies presentation time within the clip. It does not establish per-frame exposure start or UTC capture time.',
        '- Frame counts were also checked by decoding every video frame.' if args.verify_decode else '- Decoder frame counts were not requested in this run.',
        '- Extra Apple timed-metadata streams exist but were not decoded, so their contents are not claimed here.', '',
        '| Video | Frames | Time base | PTS spacing |', '| --- | ---: | --- | --- |']
    for v in audit['videos']:
        lines.append(f'| {v["name"]} | {v["frame_count"]} | {v["time_base"]} s | {v["min_interval_s"]*1000:.6f}–{v["max_interval_s"]*1000:.6f} ms |')
    lines += ['', '## Watch UTC verification limit', '', audit['verification']['watch'], '', audit['verification']['utc_mapping'], '',
        f'If startedUtc is assumed to be the first sample UTC, the implied watch boot times differ by {audit["implied_boot_disagreement_ms"]:.3f} ms. That is compatible with coarse whole-second metadata, but does not verify frame-level synchronization or quantify watch clock drift.', '',
        'Use the original monotonic t_us values for relative stitching and a shared physical event for cross-device alignment.', '',
        'Sources: [FFmpeg framecrc format](https://ffmpeg.org/ffmpeg-formats.html#framecrc), [Apple sample-buffer timestamps](https://developer.apple.com/documentation/coremedia/cmsamplebuffer/presentationtimestamp).']
    (args.output_dir/'audit.md').write_text('\n'.join(lines)+'\n')
    print(json.dumps(audit, indent=2))


if __name__ == '__main__':
    main()
