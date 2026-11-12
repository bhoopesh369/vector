#!/usr/bin/env python3
"""Upload the built motion demo only after validating the original backup."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', default='/dev/cu.usbmodem1101')
    parser.add_argument('--variant', choices=['vector_motion', 'vector_ble'], default='vector_motion')
    args = parser.parse_args()
    backup = ROOT / 'backups/factory-original-2026-11-12.bin'
    manifest = backup.with_suffix('.bin.sha256')
    if not backup.exists() or backup.stat().st_size != 32 * 1024 * 1024 or not manifest.exists():
        parser.error('A complete original flash backup and SHA-256 manifest are required')
    expected = manifest.read_text().split()[0]
    if hashlib.sha256(backup.read_bytes()).hexdigest() != expected:
        parser.error('Original backup checksum mismatch')
    build = ROOT / 'build' / args.variant
    images = [('0x0', args.variant + '.ino.bootloader.bin'),
              ('0x8000', args.variant + '.ino.partitions.bin'),
              ('0xe000', 'boot_app0.bin'), ('0x10000', args.variant + '.ino.bin')]
    for _, filename in images:
        if not (build / filename).is_file():
            parser.error('Missing build output; run sh scripts/build.sh first')
    command = [sys.executable, '-m', 'esptool', '--chip', 'esp32s3', '--port', args.port,
               '--stub-version', '2', 'write-flash', '--flash-mode', 'dio',
               '--flash-freq', '80m', '--flash-size', '32MB']
    for offset, filename in images:
        command.extend([offset, str(build / filename)])
    return subprocess.call(command, cwd=ROOT)


if __name__ == '__main__':
    raise SystemExit(main())
