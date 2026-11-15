import csv
import json
from pathlib import Path
import tempfile
import unittest
from scripts.stitch_imu import stitch


class StitchTests(unittest.TestCase):
    def write_session(self, path, rows):
        with path.open('w', newline='') as handle:
            writer = csv.writer(handle)
            writer.writerow(['seq', 't_us', 'ax_g'])
            writer.writerows(rows)
        return path

    def test_sort_sessions_preserve_values_and_real_gap(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            first = self.write_session(folder/'first.csv', [(10, 1000000, .1), (11, 2000000, .2)])
            second = self.write_session(folder/'second.csv', [(20, 5000000, .3), (21, 6000000, .4)])
            target = folder/'combined.csv'
            report = stitch([second, first], target)
            with target.open() as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual([r['t_us'] for r in rows], ['1000000', '2000000', '5000000', '6000000'])
            self.assertEqual([r['t_session_s'] for r in rows], ['0.000000', '1.000000', '4.000000', '5.000000'])
            self.assertEqual([r['session_id'] for r in rows], ['first', 'first', 'second', 'second'])
            self.assertEqual(report['sessions'][1]['gap_from_previous_s'], 3)
            self.assertEqual(report['rows'], 4)
            self.assertEqual(json.loads(target.with_suffix('.json').read_text())['duration_s'], 5)
            with self.assertRaises(FileExistsError): stitch([first, second], target)

    def test_reject_overlap_reset_and_nonmonotonic_input(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            first = self.write_session(folder/'first.csv', [(10, 1000, .1), (11, 2000, .2)])
            for rows in [[(20, 1500, .3), (21, 3000, .4)],
                         [(0, 3000, .3), (1, 4000, .4)],
                         [(20, 4000, .3), (21, 3000, .4)]]:
                second = self.write_session(folder/'second.csv', rows)
                with self.assertRaises(ValueError): stitch([first, second], folder/'combined.csv')
                self.assertFalse((folder/'combined.csv').exists())


if __name__ == '__main__':
    unittest.main()
