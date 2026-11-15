import unittest
import numpy as np
from scripts.filter_imu import process


class FilterTests(unittest.TestCase):
    def test_stationary_bias_and_gravity_preserved(self):
        t = np.arange(2400) / 1200
        v = np.tile([0., 0., 1., 2., -3., .5], (len(t), 1))
        filtered, report = process(t, v, stationary=(0., 1.))
        np.testing.assert_allclose(filtered[:, :3], v[:, :3], atol=1e-10)
        np.testing.assert_allclose(filtered[:, 3:], 0, atol=1e-10)
        np.testing.assert_allclose(report['gyro_bias_dps'], [2, -3, .5])

    def test_no_implicit_bias_calibration(self):
        t = np.arange(1200) / 1200
        v = np.tile([0., 0., 1., 2., -3., .5], (len(t), 1))
        filtered, report = process(t, v)
        np.testing.assert_allclose(filtered, v, atol=1e-10)
        self.assertIsNone(report['stationary_calibration'])

    def test_zero_phase_reduces_noise_without_shifting_signal(self):
        t = np.arange(4800) / 1200
        signal = np.sin(2*np.pi*5*t)
        noise = .4*np.sin(2*np.pi*250*t)
        v = np.column_stack([signal+noise]*6)
        filtered, _ = process(t, v)
        interior = slice(240, -240)
        self.assertLess(np.std(filtered[interior, 0]-signal[interior]), .01)
        # Signal correlation peaks at zero lag after bidirectional filtering.
        corr = np.correlate(filtered[interior, 0], signal[interior], mode='full')
        self.assertEqual(np.argmax(corr), len(signal[interior])-1)

    def test_gaps_are_not_bridged(self):
        t = np.r_[np.arange(1200)/1200, 5+np.arange(1200)/1200]
        v = np.zeros((2400, 6)); v[:1200] = 1; v[1200:] = 100
        filtered, report = process(t, v)
        self.assertEqual(report['gap_count'], 1)
        np.testing.assert_allclose(filtered[:1200], 1, atol=1e-8)
        np.testing.assert_allclose(filtered[1200:], 100, atol=1e-8)

    def test_rejects_motion_bad_timestamps_and_cutoff(self):
        t = np.arange(1200) / 1200
        v = np.tile([0., 0., 1., 2., -3., .5], (len(t), 1))
        with self.assertRaises(ValueError): process(t, v, cutoff=601)
        with self.assertRaises(ValueError): process(t*0, v)
        v[:, 0] = np.sin(50*t)
        with self.assertRaisesRegex(ValueError, 'stillness'): process(t, v, stationary=(0, .8))

    def test_short_segments_are_retained_and_reported(self):
        t = np.r_[np.arange(1200)/1200, [5., 5.001]]
        v = np.zeros((len(t), 6)); v[-2:] = 42
        filtered, report = process(t, v)
        self.assertEqual(report['short_unfiltered_segments_row_ranges'], [[1200, 1201]])
        np.testing.assert_array_equal(filtered[-2:], 42)


if __name__ == '__main__':
    unittest.main()
