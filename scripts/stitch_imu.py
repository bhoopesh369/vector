#!/usr/bin/env python3
"""Stitch sessions on a shared monotonic watch clock, preserving every timestamp and gap."""
import argparse
import csv
import json
from pathlib import Path


def stitch(inputs, output):
    sessions = []
    fields = None
    for source in inputs:
        source = Path(source)
        with source.open(newline='') as handle:
            reader = csv.DictReader(handle)
            if fields is None:
                fields = reader.fieldnames
            if not fields or reader.fieldnames != fields or 't_us' not in fields:
                raise ValueError('All inputs must have matching headers including t_us.')
            if 'session_id' in fields or 't_session_s' in fields:
                raise ValueError('Select original sessions, not an already stitched file.')
            rows = list(reader)
        if not rows:
            raise ValueError(f'Empty session: {source}')
        stamps = [int(row['t_us']) for row in rows]
        if any(b <= a for a, b in zip(stamps, stamps[1:])):
            raise ValueError(f'Timestamps must increase within {source}.')
        sessions.append((stamps[0], stamps[-1], source, rows))
    if not sessions:
        raise ValueError('At least one session is required.')
    sessions.sort(key=lambda s: s[0])
    for earlier, later in zip(sessions, sessions[1:]):
        if later[0] <= earlier[1]:
            raise ValueError('Sessions overlap on the watch clock; cannot safely stitch.')
        if 'seq' in fields and int(later[3][0]['seq']) <= int(earlier[3][-1]['seq']):
            raise ValueError('Sequence reset: these recordings may not share a continuous watch clock.')
    output = Path(output)
    report_path = output.with_suffix('.json')
    if output.exists() or report_path.exists():
        raise FileExistsError('Output exists; choose a new filename.')
    origin = sessions[0][0]
    report = {'clock': 'original t_us, assumed shared watch boot; no wall-clock rebasing',
              'origin_t_us': origin, 'rows': sum(len(s[3]) for s in sessions),
              'duration_s': (sessions[-1][1]-origin)/1e6, 'sessions': []}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields+['session_id', 't_session_s'])
        writer.writeheader()
        for i, (start, end, source, rows) in enumerate(sessions):
            report['sessions'].append({'source': str(source), 'session_id': source.stem,
                                       'start_s': (start-origin)/1e6, 'end_s': (end-origin)/1e6,
                                       'gap_from_previous_s': (start-sessions[i-1][1])/1e6 if i else 0,
                                       'rows': len(rows)})
            for row in rows:
                writer.writerow({**row, 'session_id': source.stem,
                                 't_session_s': f'{(int(row["t_us"])-origin)/1e6:.6f}'})
    with report_path.open('x') as handle:
        json.dump(report, handle, indent=2)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inputs', type=Path, nargs='+')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = stitch(args.inputs, args.output)
    except (ValueError, OSError, KeyError) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
