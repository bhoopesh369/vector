#!/usr/bin/env python3
"""Flash the factory-based recorder without writing the upper-half archive partition."""
import argparse, hashlib, json, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--port',required=True)
p.add_argument('--update-only',action='store_true',help='Code/partition update after initial full migration; assets are already installed')
p.add_argument('--dry-run',action='store_true')
p.add_argument('--restore-factory-settings',action='store_true',help='Initial migration only: restore original saved factory NVS blocks')
a=p.parse_args()
backup=ROOT/'backups/factory-original-2026-11-12.bin'
manifest=backup.with_suffix('.bin.sha256')
if not backup.exists() or backup.stat().st_size!=32*1024*1024 or hashlib.sha256(backup.read_bytes()).hexdigest()!=manifest.read_text().split()[0]:p.error('Complete verified original flash backup required')
build=ROOT/'build/watch-factory'
args=json.loads((build/'flasher_args.json').read_text())
images={int(offset,0):build/path for offset,path in args['flash_files'].items()}
if a.update_only:
    if a.restore_factory_settings:p.error('Initial settings restoration requires a full flash')
    images={offset:path for offset,path in images.items() if offset in (0,0x8000,0x200000)}
if a.restore_factory_settings:
    data=backup.read_bytes()
    for offset,size,label in [(0x9000,200*1024,'nvsfactory'),(0x3b000,840*1024,'nvs')]:
        path=ROOT/f'backups/original-{label}.bin';path.write_bytes(data[offset:offset+size]);images[offset]=path
previous_end=0
for offset,path in sorted(images.items()):
    if not path.is_file():p.error(f'Missing build image: {path.name}')
    if offset<previous_end or offset+path.stat().st_size>0x1000000:p.error('Images overlap or would modify watch recordings')
    previous_end=offset+path.stat().st_size
command=[str(ROOT/'.venv/bin/python'),'-m','esptool','--chip','esp32s3','--port',a.port,'--stub-version','2','write-flash',*args['write_flash_args']]
for offset,path in sorted(images.items()):command.extend([hex(offset),str(path)])
if a.dry_run:
    for offset,path in sorted(images.items()):print(hex(offset),path.name,path.stat().st_size)
else:raise SystemExit(subprocess.call(command,cwd=build))
