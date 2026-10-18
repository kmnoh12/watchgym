import unittest
from types import SimpleNamespace
import numpy as np
from mmfit_paper_replay import analyze, smooth


class PaperReplayTest(unittest.TestCase):
    def test_smoothing_preserves_cubic_motion_including_observed_edges(self):
        x = np.arange(201, dtype=float) / 100
        values = np.stack([x**3 - 2*x, 2*x*x + x, x], axis=1)
        np.testing.assert_allclose(smooth(values, 31), values, atol=1e-9)

    def test_known_periodic_signal_counts_ten_with_both_clock_epochs(self):
        args = SimpleNamespace(input_rate_hz=50., smoothing_seconds=.5,
            minimum_rep_seconds=.6, maximum_rep_seconds=8.)
        for source_rate in (50, 100):
            t = np.arange(0, 25, 1/source_rate)
            values = np.stack([np.cos(2*np.pi*t/2.5), np.cos(2*np.pi*t/2.5+.2),
                               np.cos(2*np.pi*t/2.5+.4)], axis=1)
            times = np.rint(t*1e9).astype(np.int64)
            events = analyze(times, values, args)
            self.assertEqual(10, len(events))
            shifted = analyze(times+1_500_000_000_000_000_000, values, args)
            self.assertEqual([x+1_500_000_000_000_000_000 for x in events], shifted)

    def test_stationary_input_does_not_generate_repetitions(self):
        args = SimpleNamespace(input_rate_hz=50., smoothing_seconds=.5,
            minimum_rep_seconds=.6, maximum_rep_seconds=8.)
        times=np.arange(1000, dtype=np.int64)*20_000_000
        self.assertEqual([], analyze(times, np.tile([0.,0.,9.80665], (1000,1)), args))


if __name__ == '__main__':
    unittest.main()
