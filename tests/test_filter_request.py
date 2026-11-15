import json
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


class FilterBridgeTests(unittest.TestCase):
    def request(self, payload):
        result = subprocess.run([sys.executable, str(ROOT/'scripts/filter_request.py')], input=json.dumps(payload), text=True, capture_output=True)
        return result, json.loads(result.stdout)

    def test_filters_six_axes_and_returns_calibration_report(self):
        payload = {'times': [i/120 for i in range(240)], 'values': [[0, 0, 1, 2, -3, .5]]*240, 'cutoff': 20, 'stationary': [0, 1]}
        result, response = self.request(payload)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(response['values']), 240)
        self.assertEqual(response['report']['gyro_bias_dps'], [2, -3, .5])
        self.assertAlmostEqual(response['values'][100][2], 1)
        self.assertAlmostEqual(response['values'][100][3], 0)

    def test_rejects_bad_cutoff_and_nonfinite_values(self):
        for value, cutoff in [(1, 100), (float('nan'), 20)]:
            result, response = self.request({'times': [i/120 for i in range(120)], 'values': [[value]*6]*120, 'cutoff': cutoff})
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('error', response)
            self.assertNotIn('values', response)


if __name__ == '__main__':
    unittest.main()
