"""Current /runs compatibility, admission, numerical and custody failure controls."""
import copy
import json
import tempfile
import unittest
from datetime import date
from pathlib import Path

from client import Driver
from corpus import encoded, generate, header, load_request, verify_manifest
from intake import snapshot
from profiles import CURRENT, CURRENT_ENGINE, LEGACY
from reference import check_result, golden_reference
import test_tools as legacy_tests

FIXTURE = Path(__file__).parent / "fixtures/usd-treasury-current.json"
STAGE = {"storage": "float64", "compute": "float64", "accumulate": "float64", "compute_rounding": "nearest"}


def current_result():
    """A run document as GET /runs/{run_id} returns it, for the npv-only golden."""
    g = golden_reference()
    return {"run_id": "fixture-job", "analytics": ["npv"], "asof": "2026-01-15", "base_currency": "USD",
            "config": {"base_currency": "USD", "precision": {**{s: dict(STAGE) for s in ("simulation", "market", "pricing")},
                                                             "by_product": {}, "by_trade": {}}},
            "trades": [{"trade_id": i, "product": "bond", "npv": v, "exposure": None, "greeks": None}
                       for i, v in zip(g["trade_ids"], g["dirty_npv"])],
            "npv": 0.0, "exposure": None, "sensitivities": None, "market_risk": None, "calibration": None,
            "warnings": []}


def exposure_case():
    body = load_request(FIXTURE)
    body.update(header(date(2026, 1, 15), .03, 8, 42, CURRENT, body["idempotency_key"]))
    result = current_result()
    result.update(analytics=["npv", "exposure"])
    result["config"].update(copy.deepcopy(body["config"]))
    times = [0.0, 30 / 365, 90 / 365, 180 / 365]

    def exposure(value):
        return {"times": times, "epe": [max(value, 0)] + [0] * 3, "ene": [max(-value, 0)] + [0] * 3}
    ids = [r["trade_id"] for r in result["trades"]]
    result["exposure"] = {"dates": body["config"]["simulation"]["dates"], "measure": "risk-neutral-pricing",
                          "netting_set": exposure(0),
                          "precision": {"backend": "cpu", "devices": ["cpu:0"], "paths": 8,
                                        "policy": {s: dict(STAGE) for s in ("simulation", "market", "pricing")}},
                          "npv_cube": {"form": "inline", "shape": [8, 3, 4], "axes": ["scenario", "date", "trade"],
                                       "items": {"axis": "trade", "ids": ids},
                                       "values": [[[0.0] * 4 for _ in range(3)] for _ in range(8)]}}
    for row in result["trades"]:
        row["exposure"] = exposure(row["npv"])
    return body, result


def done(result=None, run="fixture-job"):
    return (200, {"run_id": run, "kind": "run", "status": "done", "result": result or current_result()})


class CurrentCorpusTests(unittest.TestCase):
    def test_future_cube_and_profiles_have_distinct_date_grids(self):
        body, result = exposure_case()
        check_result(body, result)
        for mutate in (lambda r: r["exposure"]["npv_cube"]["values"][0].append([0.] * 4),
                       lambda r: r["exposure"]["netting_set"]["epe"].__setitem__(0, 1),
                       lambda r: r["trades"][0]["exposure"].update(times=[]),
                       lambda r: r["exposure"]["precision"].update(backend="tpu"),
                       lambda r: r["exposure"]["precision"].update(paths=16),
                       lambda r: r["exposure"]["npv_cube"]["items"].update(ids=[]),
                       lambda r: r["config"]["simulation"].update(seed=43)):
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                bad = copy.deepcopy(result); mutate(bad); check_result(body, bad)

    def test_profile_identity_and_run_body_are_separate_from_legacy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            generate(root / "new", trades=100, profile=CURRENT)
            generate(root / "old", trades=100, profile=LEGACY)
            new, old = verify_manifest(root / "new"), verify_manifest(root / "old")
            self.assertEqual(new["engine_revision"], CURRENT_ENGINE)
            self.assertNotEqual(new["corpus_id"], old["corpus_id"])
            body = load_request(root / "new/portfolio-0000.json")
            self.assertNotIn("schema_version", body)
            self.assertEqual(body["idempotency_key"], new["files"][0]["submission_id"])
            self.assertIs(body["keep"], True)
            self.assertEqual(load_request(root / "old/portfolio-0000.json")["schema_version"], "2")
            new["engine_revision"] = old["engine_revision"]
            (root / "new/manifest.json").write_bytes(encoded(new))
            with self.assertRaises(ValueError):
                verify_manifest(root / "new")

    def test_run_body_without_key_or_keep_is_refused(self):
        from corpus import validate
        for mutate in (lambda b: b.pop("idempotency_key"), lambda b: b.update(idempotency_key="a/b"),
                       lambda b: b.update(keep=False), lambda b: b.update(analytics=["exposure"]),
                       lambda b: b.update(outputs={"npv_cube": "inline"}), lambda b: b["config"].update(base_currency="EUR")):
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                body = load_request(FIXTURE); mutate(body); validate(body)

    def test_wrong_rows_prices_precision_and_binding_are_refused(self):
        body = load_request(FIXTURE)
        check_result(body, current_result())
        mutations = [lambda r: r.update(trades=[]), lambda r: r["trades"].reverse(),
                     lambda r: r["trades"][0].update(npv=1),
                     lambda r: r["trades"][0].update(npv=float("nan")),
                     lambda r: r["trades"][0].update(product="swap"),
                     lambda r: r.update(npv=2), lambda r: r.update(warnings=["unqualified"]),
                     lambda r: r["config"]["precision"]["market"].update(compute="float32"),
                     lambda r: r["config"]["precision"]["by_trade"].update(x=dict(STAGE)),
                     lambda r: r.update(exposure={}), lambda r: r.update(market_risk={}),
                     lambda r: r.update(analytics=["npv", "exposure"]), lambda r: r.update(asof="2026-01-16")]
        for change in mutations:
            with self.subTest(change=change), self.assertRaises(ValueError):
                result = current_result(); change(result); check_result(body, result)


class CurrentClientTests(unittest.TestCase):
    setUp = legacy_tests.ClientTests.setUp
    tearDown = legacy_tests.ClientTests.tearDown

    def replies_for_success(self):
        return [done()]

    def run_current(self, name="current", **kwargs):
        self.submit = (202, {"run_id": "fixture-job"})
        self.replies = self.replies_for_success()
        d = Driver(self.url, self.root / name, interval=.001)
        return d, d.run(FIXTURE, **kwargs)

    def test_current_routes_intake_and_offline_corruption_controls(self):
        d, result = self.run_current()
        self.assertEqual(result["outcome"], "DONE")
        self.assertEqual(self.gets, ["/runs/fixture-job"])
        self.assertEqual(self.posts, [FIXTURE.read_bytes()])
        self.assertEqual(result["submission_id"], "current-golden")
        self.assertEqual(snapshot(d.evidence)["trade_count"], 4)
        original = (d.evidence / "result.json").read_bytes()
        (d.evidence / "result.json").write_bytes(original + b" ")
        with self.assertRaises(ValueError):
            snapshot(d.evidence)
        (d.evidence / "result.json").write_bytes(original)
        acceptance = json.loads((d.evidence / "acceptance.json").read_bytes())
        acceptance["job_id"] = "different-job"
        (d.evidence / "acceptance.json").write_bytes(encoded(acceptance))
        with self.assertRaises(ValueError):
            snapshot(d.evidence)

    def test_engine_without_runs_route_is_incompatible(self):
        self.submit = (404, {"detail": "Not Found"})
        result = Driver(self.url, self.root / "refused").run(FIXTURE)
        self.assertEqual(result["outcome"], "INCOMPATIBLE_ENGINE")
        self.assertEqual(self.gets, [])

    def test_recovery_resends_the_same_body(self):
        d, result = self.run_current("recovered", recover=True)
        self.assertEqual(result["outcome"], "DONE")
        self.assertEqual(result["post_attempts"], 1)
        self.assertTrue(result["recovery"])
        self.assertEqual(self.posts, [FIXTURE.read_bytes()])

    def test_key_bound_to_another_body_is_a_mismatch(self):
        self.submit = (409, {"detail": "idempotency_key was submitted with another body"})
        d = Driver(self.url, self.root / "conflict")
        result = d.run(FIXTURE, recover=True)
        self.assertEqual(result["outcome"], "REQUEST_MISMATCH")
        self.assertEqual(self.gets, [])
        self.assertFalse((d.evidence / "acceptance.json").exists())

    def test_submission_identity_must_be_the_body_key(self):
        with self.assertRaises(ValueError):
            Driver(self.url, self.root / "other").run(FIXTURE, submission_id="other-key")

    def test_explicit_run_refuses_different_seed_or_samples(self):
        body, result = exposure_case()
        for field, value in (("seed", 43), ("samples", 16)):
            bad = copy.deepcopy(body); bad["config"]["simulation"][field] = value
            request = self.root / f"{field}.json"
            request.write_bytes(encoded(bad))
            self.replies = [done(result)]
            d = Driver(self.url, self.root / f"refused-{field}")
            outcome = d.run(request, job_id="fixture-job")
            self.assertEqual(outcome["outcome"], "REQUEST_MISMATCH")
            self.assertEqual(outcome["post_attempts"], 0)
            self.assertFalse((d.evidence / "acceptance.json").exists())
        self.assertEqual(self.posts, [])

    def test_polled_run_identity_and_terminal_states(self):
        for reply, expected in ((done(run="other-run"), "UNKNOWN"),
                                ((200, {"run_id": "fixture-job", "kind": "portfolio", "status": "done"}), "UNKNOWN"),
                                ((200, {"run_id": "fixture-job", "kind": "run", "status": "cancelled"}), "CANCELLED"),
                                ((200, {"run_id": "fixture-job", "kind": "run", "status": "failed"}), "FAILED")):
            with self.subTest(expected=expected):
                self.replies = [reply]
                d = Driver(self.url, self.root / str(len(self.gets)), interval=.001)
                self.assertEqual(d.run(FIXTURE, job_id="fixture-job")["outcome"], expected)
        self.assertEqual(self.posts, [])


if __name__ == "__main__":
    unittest.main()
