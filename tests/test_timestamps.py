import unittest
from fractions import Fraction
from scripts.audit_timestamps import parse_framecrc


class TimestampAuditTests(unittest.TestCase):
    def test_presentation_order_is_independent_of_decode_order(self):
        text = '#tb 0: 1/2400\n0, -41, 0, 20, 100, 0xabc\n0, -21, 41, 20, 90, 0xabc\n0, -1, 20, 21, 80, 0xabc\n'
        base, frames = parse_framecrc(text)
        self.assertEqual(base, Fraction(1, 2400))
        self.assertEqual([f['pts_ticks'] for f in frames], [0, 20, 41])
        self.assertEqual([f['decode_index'] for f in frames], [0, 2, 1])
        self.assertAlmostEqual(frames[2]['pts_s'], 41/2400)
        self.assertAlmostEqual(frames[0]['dts_s'], -41/2400)
        self.assertAlmostEqual(frames[1]['duration_s'], 21/2400)

    def test_rejects_missing_timebase_and_duplicate_pts(self):
        with self.assertRaises(ValueError): parse_framecrc('0,0,0,20,100,0xab')
        with self.assertRaises(ValueError): parse_framecrc('#tb 0: 1/2400\n0,0,0,20,100,0xab\n0,20,0,20,100,0xab')


if __name__ == '__main__':
    unittest.main()
