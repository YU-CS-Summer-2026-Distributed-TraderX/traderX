"""Independent count/sign checks, adversarial inputs, real loopback transport with honest doubles."""
import copy
import json
import math
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from client import Driver, MAX_REPLY
from corpus import MAX_BYTES, digest, generate, load_request, validate, verify_entry, verify_manifest
from reference import check_result, golden_reference

FIXTURE = Path(__file__).parent / "fixtures/usd-treasury-golden.json"


def result():
    g = golden_reference()
    return dict(trade_ids=g["trade_ids"], base_npv_per_trade=g["dirty_npv"], base_npv=0.,
                scenario_risk_available=False, npv_cube=[], exposure=None)


class CorpusTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_reproducibility_and_seed_difference(self):
        for name, seed in (("a", 42), ("b", 42), ("c", 43)):
            generate(self.root / name, trades=100, portfolios=2, seed=seed, mode="heterogeneous")
        self.assertEqual((self.root / "a/manifest.json").read_bytes(), (self.root / "b/manifest.json").read_bytes())
        self.assertNotEqual(digest(self.root / "a/portfolio-0000.json"), digest(self.root / "c/portfolio-0000.json"))
        ids = set()
        m = verify_manifest(self.root / "a")
        for entry in m["files"]:
            b = load_request(self.root / "a" / entry["path"])
            ts = b["trades"]
            self.assertEqual(len(ts), 100)
            self.assertEqual(sum(bool(t["coupon_schedule"]) for t in ts), 50)
            self.assertEqual(sum(t["face_amount"] > 0 for t in ts), 50)
            self.assertEqual(sum(t["face_amount"] for t in ts), entry["economics"]["net_face_usd"])
            self.assertEqual(sum(abs(t["face_amount"]) for t in ts), entry["economics"]["gross_face_usd"])
            for t in ts:
                self.assertNotIn(t["trade_id"], ids)
                ids.add(t["trade_id"])
        self.assertEqual(len(ids), 200)

    def test_calendar_edges_and_scenarios(self):
        for asof in ("2024-02-29", "2026-01-31", "2026-08-31", "2026-12-31"):
            p = self.root / asof
            generate(p, trades=12, asof=asof, mode="heterogeneous", samples=8)
            body = load_request(p / "portfolio-0000.json")
            self.assertTrue(body["scenario_risk"])
            self.assertEqual(verify_manifest(p)["cube_bytes_per_portfolio_lower_bound"], 8 * 4 * 12 * 8)

    def test_limits_and_atomic_cleanup(self):
        for kw in ({"trades": 0}, {"trades": 100001}, {"trades": 1000, "portfolios": 101},
                   {"seed": -1}, {"samples": 129}, {"trades": 100000, "samples": 128},
                   {"rate": math.nan}, {"rate": .3}, {"max_bytes": 100}, {"max_bytes": MAX_BYTES + 1}):
            with self.subTest(kw=kw), self.assertRaises(ValueError):
                generate(self.root / "bad", **kw)
            self.assertFalse((self.root / "bad").exists())
            self.assertFalse(list(self.root.glob(".ri14-*")))

    def test_no_overwrite_or_checkout_output(self):
        generate(self.root / "a", trades=4)
        with self.assertRaises(ValueError):
            generate(self.root / "a", trades=4)
        (self.root / ".git").touch()
        with self.assertRaises(ValueError):
            generate(self.root / "b", trades=4)

    def test_checksum_tampering(self):
        generate(self.root / "a", trades=4)
        with (self.root / "a/portfolio-0000.json").open("ab") as f:
            f.write(b" ")
        with self.assertRaises(ValueError):
            verify_manifest(self.root / "a")

    def test_manifest_identity_and_economics_controls(self):
        generate(self.root / "a", trades=4)
        m = verify_manifest(self.root / "a")
        body = load_request(self.root / "a/portfolio-0000.json")
        verify_entry(body, m["files"][0])
        bad = copy.deepcopy(m["files"][0])
        bad["economics"]["net_face_usd"] = 5
        with self.assertRaises(ValueError):
            verify_entry(body, bad)
        m["files"][0]["submission_id"] = "foreign"
        (self.root / "a/manifest.json").write_text(json.dumps(m))
        with self.assertRaises(ValueError):
            verify_manifest(self.root / "a")

    def test_negative_inputs(self):
        good = load_request(FIXTURE)
        mutations = [lambda b: b["trades"][0].update(currency="EUR"),
                     lambda b: b["trades"][0].update(trade_type="equity"),
                     lambda b: b["trades"][0].update(face_amount=math.nan),
                     lambda b: b["trades"][0].update(face_amount=0),
                     lambda b: b["trades"][0].update(maturity_date="2025-01-15"),
                     lambda b: b["trades"][1].update(trade_id=b["trades"][0]["trade_id"]),
                     lambda b: b["trades"][2].update(coupon_schedule=[]),
                     lambda b: b["trades"][2]["coupon_schedule"][1].update(start_date="2026-04-16"),
                     lambda b: b["trades"][2]["coupon_schedule"][0].update(payment_date="2026-04-14"),
                     lambda b: b["trades"][2].update(accrual_day_count="SOFR"),
                     lambda b: b.update(portfolio_id="not-an-engine-field"),
                     lambda b: b.update(trades=[]), lambda b: b.update(base_currency="EUR")]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                bad = copy.deepcopy(good)
                mutate(bad)
                with self.assertRaises(ValueError):
                    validate(bad)

    def test_golden_reference_accounting_and_negative_controls(self):
        g = golden_reference()
        self.assertAlmostEqual(g["dirty_npv"][0], 100000 * math.exp(-.03), places=9)
        self.assertEqual(sum(g["dirty_npv"]), 0)
        self.assertEqual(g["dirty_npv"][2] - g["accrued_usd"][2], g["clean_npv"][2])
        b = load_request(FIXTURE)
        self.assertEqual(check_result(b, result()), "small-analytic-golden-passed")
        for mutate in (lambda r: r.update(trade_ids=[]), lambda r: r.update(base_npv=10),
                       lambda r: r["base_npv_per_trade"].__setitem__(0, -1),
                       lambda r: r["base_npv_per_trade"].__setitem__(0, math.nan),
                       lambda r: r.update(npv_cube=[1]), lambda r: r.update(scenario_risk_available=True),
                       lambda r: r.update(base_npv_per_trade=[1000, -1000, 1000, -1000])):
            r = result()
            mutate(r)
            with self.assertRaises(ValueError):
                check_result(b, r)


class ClientTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.posts, self.gets, self.keys = [], [], []
        self.submit = (202, {"job_id": "fixture-job"})
        self.replies = [(200, {"status": "done", "result": result()})]
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                outer.posts.append(self.rfile.read(int(self.headers["Content-Length"])))
                outer.keys.append(self.headers.get("Idempotency-Key"))
                if outer.submit is None:
                    self.close_connection = True
                    return
                self.reply(*outer.submit)

            def do_GET(self):
                outer.gets.append(self.path)
                current = outer.replies.pop(0) if len(outer.replies) > 1 else outer.replies[0]
                self.reply(*current)

            def reply(self, code, body):
                raw = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def driver(self, **kwargs):
        return Driver(self.url, self.root / "evidence", interval=.001, **kwargs)

    def test_submission_poll_and_custody(self):
        self.replies.insert(0, (200, {"status": "running"}))
        d = self.driver()
        r = d.run(FIXTURE, submission_id="synthetic-golden")
        self.assertEqual(r["outcome"], "DONE")
        self.assertEqual(len(self.posts), 1)
        self.assertEqual(self.keys, ["synthetic-golden"])
        self.assertEqual(self.posts[0], FIXTURE.read_bytes())
        self.assertEqual((d.evidence / "request.json").read_bytes(), FIXTURE.read_bytes())
        self.assertEqual(len(r["events"]), 3)
        for event in r["events"]:
            self.assertEqual(event["sha256"], digest(d.evidence / event["file"]))

    def test_uncertain_500_and_lost_ack_no_retry(self):
        for submit in ((500, {"detail": "startup failure"}), None, (202, {}), (202, [])):
            with self.subTest(submit=submit):
                self.submit = submit
                d = Driver(self.url, self.root / str(len(self.posts)), interval=.001)
                r = d.run(FIXTURE)
                self.assertEqual(r["outcome"], "SUBMISSION_UNKNOWN")
                self.assertEqual(r["post_attempts"], 1)
                self.assertEqual(len(self.gets), 0)
        self.assertEqual(len(self.posts), 4)

    def test_terminal_and_unknown_states(self):
        cases = [("failed", 200, "FAILED"), ("interrupted", 200, "INTERRUPTED"),
                 ("new-state", 200, "UNKNOWN"), ("pending", 404, "UNKNOWN"),
                 ("pending", 503, "UNKNOWN"), ("pending", 200, "TIMEOUT")]
        for i, (status, code, expected) in enumerate(cases):
            with self.subTest(status=status, code=code):
                self.replies = [(code, {"status": status})]
                d = Driver(self.url, self.root / str(i), interval=.001, max_polls=2)
                r = d.run(FIXTURE, job_id="fixture-job")
                self.assertEqual(r["outcome"], expected)
                self.assertEqual(r["post_attempts"], 0)
        self.assertEqual(self.posts, [])

    def test_recovery_uses_only_get(self):
        self.replies.insert(0, (200, {"job_id": "fixture-job"}))
        r = self.driver().run(FIXTURE, submission_id="synthetic-golden", recover=True)
        self.assertEqual(r["outcome"], "DONE")
        self.assertEqual(self.gets[0], "/portfolio/submissions/synthetic-golden")
        self.assertEqual(self.posts, [])

    def test_rejection_no_poll(self):
        self.submit = (429, {"detail": "full"})
        self.assertEqual(self.driver().run(FIXTURE)["outcome"], "REJECTED")
        self.assertEqual(self.gets, [])

    def test_invalid_result(self):
        for i, invalid in enumerate(({}, None, [], 0)):
            self.replies = [(200, {"status": "done", "result": invalid})]
            d = Driver(self.url, self.root / str(i), interval=.001)
            self.assertEqual(d.run(FIXTURE)["outcome"], "INVALID_RESULT")

    def test_deadline_and_keyboard_interrupt_keep_identity(self):
        d = self.driver(deadline=.000001)
        r = d.run(FIXTURE, job_id="saved-id")
        self.assertEqual(r["outcome"], "TIMEOUT")
        self.assertEqual(r["job_id"], "saved-id")
        other = Driver(self.url, self.root / "interrupt")
        with patch.object(other, "exchange", side_effect=KeyboardInterrupt):
            r = other.run(FIXTURE)
        self.assertEqual(r["outcome"], "CLIENT_INTERRUPTED")
        self.assertEqual(r["post_attempts"], 1)

    def test_byte_limit_preserves_partial_reply(self):
        self.submit = (202, {"padding": "x" * 100})
        d = self.driver()
        with patch("client.MAX_REPLY", 20):
            self.assertEqual(d.run(FIXTURE)["outcome"], "SUBMISSION_UNKNOWN")
        self.assertTrue((d.evidence / "reply-0000.bin").exists())
        self.assertEqual(len(self.posts), 1)

    def test_remote_and_invalid_timings_refused(self):
        for url in ("http://example.com", "https://127.0.0.1", "http://user@localhost", "http://localhost/path"):
            with self.assertRaises(ValueError):
                Driver(url, self.root / "bad")
        for timing in (0, -1, math.inf, math.nan):
            with self.assertRaises(ValueError):
                Driver(self.url, self.root / "bad", timeout=timing)


if __name__ == "__main__":
    unittest.main()
