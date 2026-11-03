"""Tests for bench.py's battery gate and session log: `py -m unittest discover tools/bench` from the repository root."""
import contextlib
import io
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


class NoResultLogTest(unittest.TestCase):
    """A session that times out reports its own log, not the restore start's."""

    def setUp(self):
        self.log = ["Bench: session started", "Bench: waiting for page 3"]
        self.saved = bench.adb, bench.time.sleep, bench.time.time

        def adb(*cmd, capture=False, check=True, timeout=None):
            if cmd[:2] == ("logcat", "-c"):
                self.log = []
            elif cmd[:2] == ("logcat", "-d"):
                return "\n".join(self.log)
            elif cmd[:2] == ("shell", "am") and cmd[2] == "start" and len(cmd) == 6:
                # The empty start that puts the settings back logs its own run.
                self.log += ["Bench: restore run", "Bench: Bench done"]
            elif cmd[:2] == ("shell", "am") and cmd[2] == "start":
                self.log += ["Bench: session started", "Bench: waiting for page 3"]
            return ""

        clock = iter(range(0, 1000, 10))
        bench.adb, bench.time.sleep, bench.time.time = adb, lambda s: None, lambda: next(clock)

    def tearDown(self):
        bench.adb, bench.time.sleep, bench.time.time = self.saved

    def test_timeout_reports_the_sessions_log(self):
        err = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            self.assertEqual([], bench.bench_once({"label": "book"}, 30))
        self.assertIn("waiting for page 3", err.getvalue())
        self.assertNotIn("restore run", err.getvalue())


if __name__ == "__main__":
    unittest.main()
