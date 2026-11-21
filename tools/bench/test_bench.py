"""Tests for bench.py's battery gate, session log, thermal readings and build info: `py -m unittest discover tools/bench` from the repository root."""
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


class ThermalTemps(unittest.TestCase):
    DUMP = """Thermal Status: 0
Cached temperatures:
\tTemperature{mValue=99.0, mType=2, mName=OLD, mStatus=0}
HAL Ready: true
Current temperatures from HAL:
\tTemperature{mValue=22.7, mType=0, mName=AP, mStatus=0}
\tTemperature{mValue=27.0, mType=2, mName=BAT, mStatus=0}
\tTemperature{mValue=0.0, mType=2, mName=SUB, mStatus=0}
Current cooling devices from HAL:
\tCoolingDevice{mValue=0, mType=0, mName=fan}
"""

    def test_reads_the_hal_section_only_and_drops_zeros(self):
        self.assertEqual({"AP": 22.7, "BAT": 27.0}, bench.thermal_temps(self.DUMP))

    def test_no_section_reads_nothing(self):
        self.assertEqual({}, bench.thermal_temps("Thermal Status: 0\n"))

    def test_summary_has_start_peak_and_end(self):
        s = bench.temp_summary([{"AP": 30.0}, {"AP": 34.5, "BAT": 27.0}, {"AP": 33.0, "BAT": 27.5}])
        self.assertEqual({"start": 30.0, "max": 34.5, "end": 33.0}, s["AP"])
        self.assertEqual({"start": 27.0, "max": 27.5, "end": 27.5}, s["BAT"])


class Extras(unittest.TestCase):
    def test_a_wide_pages_choice_is_a_string_extra(self):
        self.assertEqual(["--es", "wide", "image", "--ei", "turns", "4", "--ez", "fresh", "true"],
                         bench.extras({"wide": "image", "turns": 4, "fresh": True}))


class PackageInfo(unittest.TestCase):
    def test_reads_version_and_install_time(self):
        text = "    versionCode=1234 minSdk=26 targetSdk=36\n    lastUpdateTime=2026-01-02 03:04:05\n"
        self.assertEqual({"versionCode": 1234, "lastUpdateTime": "2026-01-02 03:04:05"}, bench.package_info(text))


if __name__ == "__main__":
    unittest.main()
