"""Tests for bench.py's battery gate: `py -m unittest discover tools/bench` from the repository root."""
import unittest

import bench


class BatteryGateTest(unittest.TestCase):
    def test_default_minimum_is_20(self):
        for cmd in (["run", "--plan", "p.json", "--out", "o"], ["ab", "--a", "a.apk", "--b", "b.apk", "--plan", "p.json", "--out", "o"]):
            self.assertEqual(bench.build_parser().parse_args(cmd).min_battery, 20)

    def test_refuses_below_minimum_only(self):
        self.assertIsNotNone(bench.battery_refusal(19, 20))
        self.assertIsNone(bench.battery_refusal(20, 20))
        self.assertIsNone(bench.battery_refusal(85, 20))

    def test_unknown_level_never_refuses(self):
        self.assertIsNone(bench.battery_refusal(None, 20))

    def test_zero_overrides(self):
        args = bench.build_parser().parse_args(["run", "--plan", "p.json", "--out", "o", "--min-battery", "0"])
        self.assertIsNone(bench.battery_refusal(0, args.min_battery))
        self.assertIsNone(bench.battery_refusal(1, args.min_battery))

    def test_refusal_names_the_override(self):
        self.assertIn("--min-battery 0", bench.battery_refusal(5, 20))


if __name__ == "__main__":
    unittest.main()
