#!/usr/bin/env python3
"""Install the pinned Mac/Apple Silicon toolchain inside this project."""
import hashlib
from pathlib import Path
import platform
import subprocess
import sys
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
VENDOR_COMMIT = '30f3b23b3d4bb38d32f7d0cddffb66759668a855'
CLI_SHA256 = 'dc70f037fcbc8e7add892282bed5bab5eac317ff00bf0dd2f21e4a8e1d338a1c'


def run(*args):
    subprocess.run(args, cwd=ROOT, check=True)


def main():
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        raise SystemExit('This setup script currently supports Apple Silicon Macs only.')
    for folder in ['.tools', '.cache', '.vendor', 'backups', 'recordings']:
        (ROOT / folder).mkdir(exist_ok=True)
    cli = ROOT / '.tools/arduino-cli'
    if not cli.exists():
        archive = ROOT / '.tools/arduino-cli_1.6.0_macOS_ARM64.tar.gz'
        urllib.request.urlretrieve(
            'https://github.com/arduino/arduino-cli/releases/download/v1.6.0/' + archive.name, archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != CLI_SHA256:
            raise SystemExit('Arduino CLI download checksum mismatch')
        with tarfile.open(archive) as package:
            package.extract('arduino-cli', path=cli.parent, filter='data')
    run(str(cli), 'version')
    config = ROOT / '.cache/arduino-cli.yaml'
    config.write_text('board_manager:\n  additional_urls:\n'
                      '    - https://espressif.github.io/arduino-esp32/package_esp32_index.json\n'
                      'directories:\n'
                      f'  data: {ROOT / ".tools/arduino-data"}\n'
                      f'  downloads: {ROOT / ".cache/arduino-downloads"}\n'
                      f'  user: {ROOT / ".tools/arduino-user"}\n')
    vendor = ROOT / '.vendor/waveshare'
    if not vendor.exists():
        run('git', 'init', str(vendor))
        run('git', '-C', str(vendor), 'remote', 'add', 'origin',
            'https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-2.06.git')
        run('git', '-C', str(vendor), 'fetch', '--depth', '1', 'origin', VENDOR_COMMIT)
        run('git', '-C', str(vendor), 'checkout', '--detach', 'FETCH_HEAD')
    actual = subprocess.check_output(['git', '-C', str(vendor), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != VENDOR_COMMIT:
        raise SystemExit('Vendor checkout differs from pinned revision; inspect it before continuing.')
    run(sys.executable, '-m', 'venv', str(ROOT / '.venv'))
    run(str(ROOT / '.venv/bin/python'), '-m', 'pip', 'install', '--no-cache-dir', '-r', 'requirements.txt')
    run(str(cli), '--config-file', str(config), 'core', 'update-index')
    run(str(cli), '--config-file', str(config), 'core', 'install', 'esp32:esp32@3.3.11')


if __name__ == '__main__':
    main()
