import json
from pathlib import Path
import tempfile
import unittest
from scripts.catalog_sessions import library

class LibraryTests(unittest.TestCase):
    def test_metadata_matching_and_non_sensor_csv_exclusion(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            data=root/'samples/vector-motion-sessions/recordings'
            data.mkdir(parents=True)
            meta=data.parent/'metadata'
            meta.mkdir()
            (data/'valid.csv').write_text('seq,t_us,ax_g\n1,1794728549338522,1\n')
            (meta/'valid.csv.json').write_text(json.dumps({'timestampUnit':'unix_microseconds'}))
            (data/'sessions.csv').write_text('recording,started_utc\nx,2026\n')
            (root/'samples/videos').mkdir()
            (root/'samples/videos/clip.mp4').write_bytes(b'fixture')
            result=library(root)
            self.assertEqual(len(result['sessions']),1)
            self.assertEqual(result['sessions'][0]['metadata']['timestampUnit'],'unix_microseconds')
            self.assertEqual(result['sessions'][0]['firstUs'],1794728549338522)
            self.assertEqual(len(result['videos']),1)
