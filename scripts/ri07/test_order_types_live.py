"""Negative controls for scripts/ri07/order-types-live.py (review R1, R2). No rig needed.

Run: python3 -m unittest discover -s scripts/ri07 -p 'test_*.py' -v
"""
import importlib.util, io, json, subprocess, unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

spec = importlib.util.spec_from_file_location("otl", Path(__file__).with_name("order-types-live.py"))
otl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(otl)
ENV = {"CONSOLE_URL": "http://127.0.0.1:9", "CTX": "kind-test"}


def proc(rc=0, out="", err=""):
    return subprocess.CompletedProcess([], rc, out, err)


class SqlAndBbo(unittest.TestCase):
    def setUp(self):
        otl.configure(ENV)

    def test_failed_sql_raises_instead_of_empty(self):
        with mock.patch.object(otl.subprocess, "run", return_value=proc(1, "", "error 2002 can't connect")):
            with self.assertRaises(otl.QueryFailed):
                otl.sql("SELECT 1", 1)

    def test_failed_before_after_reads_cannot_look_like_no_prints(self):
        # The FOK case compares len(trades(T)) before and after; two failed reads used to be two [].
        with mock.patch.object(otl.subprocess, "run", return_value=proc(1, "", "boom")):
            with self.assertRaises(otl.QueryFailed):
                otl.trades("OTA1")

    def test_malformed_row_raises(self):
        with mock.patch.object(otl.subprocess, "run", return_value=proc(0, "22214\tBuy\n")):
            with self.assertRaises(otl.QueryFailed):
                otl.trades("OTA1")

    def test_wellformed_rows_parse(self):
        with mock.patch.object(otl.subprocess, "run", return_value=proc(0, "22214\tBuy\t3\t101.000000\n")):
            self.assertEqual(otl.trades("OTA1"), [["22214", "Buy", "3", "101.000000"]])

    def test_empty_result_is_empty_not_error(self):
        with mock.patch.object(otl.subprocess, "run", return_value=proc(0, "")):
            self.assertEqual(otl.trades("OTA1"), [])

    def test_bbo_failure_and_garbage_raise(self):
        for p in (proc(1, "", "pod not found"), proc(0, "<html>"), proc(0, '{"nobooks":1}')):
            with self.subTest(p=p), mock.patch.object(otl.subprocess, "run", return_value=p):
                with self.assertRaises(otl.QueryFailed):
                    otl.bbo("OTA1")

    def test_bbo_absent_ticker_is_empty(self):
        with mock.patch.object(otl.subprocess, "run", return_value=proc(0, json.dumps({"books": [{"ticker": "X"}]}))):
            self.assertEqual(otl.bbo("OTA1"), {})


class Verdict(unittest.TestCase):
    ok = {"pass": True, "knownGap": None}
    bad = {"pass": False, "knownGap": None}
    gap_open = {"pass": False, "knownGap": "F3"}
    gap_closed = {"pass": True, "knownGap": "F3"}

    def test_all_pass_including_gap_is_acceptance(self):
        self.assertEqual(otl.verdict([self.ok, self.gap_closed])[0], 0)

    def test_open_known_gap_is_incomplete_never_zero(self):
        code, text = otl.verdict([self.ok, self.gap_open])
        self.assertEqual(code, 3)
        self.assertIn("INCOMPLETE", text)
        self.assertNotIn("VERDICT: PASS", text)

    def test_ordinary_mismatch_fails(self):
        self.assertEqual(otl.verdict([self.bad, self.gap_open])[0], 1)
        self.assertEqual(otl.verdict([self.bad, self.gap_closed])[0], 1)

    def test_no_cases_is_not_a_pass(self):
        self.assertEqual(otl.verdict([])[0], 1)


class MainAborts(unittest.TestCase):
    def test_unreadable_bbo_in_precondition_aborts_nonzero(self):
        with mock.patch.object(otl, "http", return_value=(200, {})), \
             mock.patch.object(otl.subprocess, "run", return_value=proc(1, "", "no such pod")), \
             redirect_stdout(io.StringIO()) as out:
            code = otl.main(["x"], ENV)
        self.assertEqual(code, 1)
        self.assertIn("ABORTED", out.getvalue())

    def test_missing_env_refuses(self):
        with redirect_stdout(io.StringIO()):
            self.assertEqual(otl.main(["x"], {}), 2)


if __name__ == "__main__":
    unittest.main()
