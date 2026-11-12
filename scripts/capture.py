#!/usr/bin/env python3
"""Capture the Vector USB motion demo without toggling DTR/RTS."""
import argparse
import csv
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import time

HEADER = ['seq', 't_us', 'ax_g', 'ay_g', 'az_g', 'gx_dps', 'gy_dps', 'gz_dps', 'clipped']


@contextmanager
def recording_files(output):
    if output.suffix.lower() != '.csv':
        raise ValueError('--output must end in .csv')
    metadata = output.with_suffix('.json')
    if output.exists() or metadata.exists():
        raise FileExistsError('Recording or metadata already exists; choose a new filename')
    with output.open('x', newline='') as csv_file:
        try:
            json_file = metadata.open('x')
        except OSError:
            output.unlink()  # Only the empty CSV just created by this call.
            raise
        with json_file:
            yield csv_file, json_file


def parse_sample(line):
    line = line.strip()
    if not line or line.startswith('#') or line == ','.join(HEADER):
        return None
    parts = line.split(',')
    if len(parts) != 9:
        raise ValueError('Expected nine CSV fields')
    seq, timestamp = int(parts[0]), int(parts[1])
    axes = tuple(float(value) for value in parts[2:8])
    clipped = int(parts[8])
    if seq < 0 or timestamp < 0 or clipped not in (0, 1) or not all(map(math.isfinite, axes)):
        raise ValueError('Invalid sample values')
    return (seq, timestamp, *axes, clipped)


@dataclass
class CaptureStats:
    samples: int = 0
    missing_sequences: int = 0
    clipped: int = 0
    max_interval_us: int = 0
    first_us: int | None = None
    last_us: int | None = None
    last_seq: int | None = None

    def add(self, sample):
        seq, timestamp = sample[:2]
        if self.last_seq is not None:
            if seq <= self.last_seq or timestamp <= self.last_us:
                raise ValueError('Watch restarted or duplicate/out-of-order sample; start a new capture')
            self.missing_sequences += seq - self.last_seq - 1
            self.max_interval_us = max(self.max_interval_us, timestamp - self.last_us)
        if self.first_us is None:
            self.first_us = timestamp
        self.last_seq, self.last_us = seq, timestamp
        self.samples += 1
        self.clipped += sample[8]

    def report(self):
        seconds = (self.last_us - self.first_us) / 1e6 if self.samples > 1 else 0
        return {**vars(self), 'duration_s': seconds,
                'received_hz': (self.samples - 1) / seconds if seconds else 0}


def main():
    import serial
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', default='/dev/cu.usbmodem1101')
    parser.add_argument('--seconds', type=float, default=30)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if not math.isfinite(args.seconds) or args.seconds <= 0:
        parser.error('--seconds must be finite and positive')
    started = datetime.now(timezone.utc)
    output = args.output or Path('recordings') / (started.strftime('%Y%m%dT%H%M%SZ') + '.csv')
    output.parent.mkdir(parents=True, exist_ok=True)
    stats, malformed, notes = CaptureStats(), 0, []
    error = None
    port = serial.Serial(port=None, baudrate=115200, timeout=0.25)
    port.dtr = False
    port.rts = False
    port.port = args.port
    # Exclusive creation prevents accidental replacement of an earlier session.
    with recording_files(output) as (file, metadata_file):
        writer = csv.writer(file)
        writer.writerow(HEADER)
        try:
            port.open()
            start = last_valid = last_print = time.monotonic()
            while time.monotonic() - start < args.seconds:
                raw = port.read_until(b'\n', size=512)
                if raw:
                    line = raw.decode('ascii', errors='replace').strip()
                    if line.startswith('#'):
                        if len(notes) < 100:
                            notes.append(line)
                        print(line, flush=True)
                    try:
                        sample = parse_sample(line)
                    except ValueError:
                        malformed += 1
                        sample = None
                    if sample is not None:
                        stats.add(sample)
                        writer.writerow(sample)
                        last_valid = time.monotonic()
                        if last_valid - last_print >= 1:
                            file.flush()
                            print(f'{stats.samples:6d} samples | acc {sample[2:5]} g | gyro {sample[5:8]} deg/s', flush=True)
                            last_print = last_valid
                if time.monotonic() - last_valid > 5:
                    raise RuntimeError('No valid motion samples for five seconds')
            if stats.samples < 2:
                raise RuntimeError('Not enough motion samples received')
        except KeyboardInterrupt:
            error = 'Capture interrupted by user'
        except (serial.SerialException, OSError, ValueError, RuntimeError) as exc:
            error = str(exc)
        finally:
            port.close()
        report = {**stats.report(), 'started_utc': started.isoformat(), 'port': args.port,
                  'malformed_lines': malformed, 'notes': notes, 'error': error}
        metadata_file.write(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    print(f'Saved {output.resolve()}')
    return 1 if error else 0


if __name__ == '__main__':
    raise SystemExit(main())
