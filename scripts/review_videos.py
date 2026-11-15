#!/usr/bin/env python3
"""Create local contact sheets and frame-difference traces without modifying videos."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import subprocess
import imageio_ffmpeg
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]


def review(path, output):
    exe = imageio_ffmpeg.get_ffmpeg_exe()
    metadata = subprocess.run([exe, '-hide_banner', '-i', str(path)], capture_output=True, text=True).stderr
    match = re.search(r'Duration: (\d+):(\d+):([\d.]+)', metadata)
    if not match:
        raise ValueError(f'Cannot read duration for {path}')
    duration = int(match[1])*3600 + int(match[2])*60 + float(match[3])
    fps = 30
    command = [exe, '-v', 'error', '-i', str(path), '-map', '0:v:0', '-vf', f'fps={fps},scale=180:320', '-pix_fmt', 'rgb24', '-f', 'rawvideo', '-']
    process = subprocess.Popen(command, stdout=subprocess.PIPE)
    frames, differences = [], []
    previous = None
    index = 0
    stride = 60 if duration > 20 else 15
    while True:
        block = process.stdout.read(180*320*3)
        if not block:
            break
        if len(block) != 180*320*3:
            raise RuntimeError('Incomplete decoded frame')
        frame = np.frombuffer(block, dtype=np.uint8).reshape(320, 180, 3)
        gray = frame.mean(axis=2)
        # Full frame difference is an exploratory cue, not a wrist-motion measure.
        differences.append(0 if previous is None else float(np.abs(gray-previous).mean()))
        previous = gray
        if index % stride == 0:
            frames.append((index/fps, Image.fromarray(frame.copy())))
        index += 1
    if process.wait() != 0:
        raise RuntimeError('Decoder failed: '+str(path))
    columns = 7
    rows = (len(frames)+columns-1)//columns
    sheet = Image.new('RGB', (columns*180, rows*347), '#10151b')
    draw = ImageDraw.Draw(sheet)
    for n, (seconds, frame) in enumerate(frames):
        x, y = n % columns * 180, n // columns * 347
        sheet.paste(frame, (x, y))
        draw.text((x+7, y+325), f'{path.stem} / {seconds:.2f}s', fill='white')
    sheet.save(output / (path.stem+'-contact.jpg'), quality=90)
    data = {'name': path.name, 'duration_s': duration, 'analysis_fps': fps,
            'difference': differences, 'note': 'Decoded sampling at 30 fps; full-image differences include camera and other people motion.'}
    (output / (path.stem+'-motion.json')).write_text(json.dumps(data))
    print(path.name, duration, 'seconds;', len(differences), 'sampled frames', flush=True)
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'recordings/video-review')
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(lambda path: review(path, args.output_dir), sorted((ROOT/'samples/videos').glob('*.MOV'))))


if __name__ == '__main__':
    main()
