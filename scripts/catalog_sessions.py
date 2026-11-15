"""Small library manifest; never reads entire recordings or videos into memory."""
import csv
import json
from pathlib import Path
from urllib.parse import quote
ROOT = Path(__file__).resolve().parents[1]
def library(root=ROOT):
    sessions = []
    for path in sorted((root/'samples').rglob('*.csv')):
        if path.name == 'sessions.csv':
            continue
        sidecar = path.parent.parent/'metadata'/(path.name+'.json')
        if not sidecar.exists():
            sidecar = path.parent/'session.json'
        try:
            metadata = json.loads(sidecar.read_text()) if sidecar.exists() else {}
            with path.open() as handle:
                reader = csv.DictReader(handle)
                first = next(reader)
            if 't_us' not in first:
                continue
            sessions.append(dict(name=path.name, url=quote(path.relative_to(root).as_posix()),
                                 size=path.stat().st_size, firstUs=float(first['t_us']), metadata=metadata))
        except (ValueError, OSError, StopIteration):
            continue
    videos = [dict(name=p.name, url=quote(p.relative_to(root).as_posix()), size=p.stat().st_size)
              for p in sorted((root/'samples/videos').glob('*')) if p.suffix.lower() in ('.mp4', '.mov')]
    return dict(sessions=sessions, videos=videos)
if __name__ == '__main__':
    print(json.dumps(library()))
