import unittest
import tempfile
from pathlib import Path

from scripts.capture import CaptureStats, HEADER, parse_sample, recording_files


class CaptureTests(unittest.TestCase):
    def test_rejects_non_csv_output_without_creating_files(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / 'session.json'
            with self.assertRaises(ValueError), recording_files(target):
                pass
            self.assertFalse(target.exists())

    def test_existing_recording_or_sidecar_is_never_overwritten(self):
        for suffix in ['.csv', '.json']:
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as folder:
                target = Path(folder) / 'session.csv'
                existing = target.with_suffix(suffix)
                existing.write_text('precious data')
                with self.assertRaises(FileExistsError), recording_files(target):
                    pass
                self.assertEqual(existing.read_text(), 'precious data')
                self.assertEqual(len(list(Path(folder).iterdir())), 1)

    def test_reserves_separate_recording_and_sidecar(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / 'session.csv'
            with recording_files(target) as (csv_file, json_file):
                csv_file.write('readings')
                json_file.write('{}')
            self.assertEqual(target.read_text(), 'readings')
            self.assertEqual(target.with_suffix('.json').read_text(), '{}')

    def test_reads_six_axes_with_explicit_units(self):
        sample = parse_sample('42,1000000,0.1,-0.2,1.0,3.5,-4.5,5.5,0')
        self.assertEqual(sample[:2], (42, 1000000))
        self.assertEqual(sample[2:8], (0.1, -0.2, 1.0, 3.5, -4.5, 5.5))
        self.assertEqual(HEADER[2:8], ['ax_g', 'ay_g', 'az_g', 'gx_dps', 'gy_dps', 'gz_dps'])

    def test_ignores_comments_and_header(self):
        self.assertIsNone(parse_sample('# sensor ready'))
        self.assertIsNone(parse_sample(','.join(HEADER)))
        self.assertIsNone(parse_sample(''))

    def test_rejects_malformed_nonfinite_and_invalid_fields(self):
        for line in ['1,2,3', '1,2,nan,0,1,0,0,0,0', '1,2,0,0,1,inf,0,0,0',
                     '-1,2,0,0,1,0,0,0,0', '1,2,0,0,1,0,0,0,2', 'boot log']:
            with self.subTest(line=line), self.assertRaises(ValueError):
                parse_sample(line)

    def test_reports_gaps_and_clipping(self):
        stats = CaptureStats()
        stats.add(parse_sample('10,10000,0,0,1,0,0,0,0'))
        stats.add(parse_sample('13,40000,0,0,1,0,0,0,1'))
        self.assertEqual(stats.missing_sequences, 2)
        self.assertEqual(stats.clipped, 1)
        self.assertEqual(stats.max_interval_us, 30000)

    def test_rejects_restarts_and_duplicate_timestamps(self):
        for second in ['0,20000,0,0,1,0,0,0,0', '11,10000,0,0,1,0,0,0,0']:
            stats = CaptureStats()
            stats.add(parse_sample('10,10000,0,0,1,0,0,0,0'))
            with self.assertRaises(ValueError):
                stats.add(parse_sample(second))


if __name__ == '__main__':
    unittest.main()
