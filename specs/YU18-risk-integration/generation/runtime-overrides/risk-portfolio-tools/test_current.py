"""Current API compatibility, admission, numerical and custody failure controls."""
import copy
import json
import tempfile
import unittest
from pathlib import Path

from client import Driver
from corpus import digest, encoded, generate, header, load_request, verify_manifest
from intake import snapshot
from profiles import CONTRACT, CURRENT, CURRENT_ENGINE, CONTENT_DIGEST_ALGORITHM, LEGACY
from reference import check_result, golden_reference
from request_identity import content_sha256
import test_tools as legacy_tests

FIXTURE = Path(__file__).parent / "fixtures/usd-treasury-current.json"


def current_result():
    g = golden_reference()
    return {"trades": [{"trade_id": i, "base_npv": v, "exposure": None, "greeks": None}
                       for i, v in zip(g["trade_ids"], g["dirty_npv"])],
            "base_npv": 0.0, "npv_cube": [], "npv_cube_artifact": None, "exposure": None,
            "measure": None, "scenario_risk_available": False, "warnings": [],
            "precision": {"backend": "cpu", "devices": ["cpu:0"],
                          "policy": {s: {f: "float64" for f in ("compute", "storage", "accumulate")}
                                     for s in ("simulation", "market", "pricing")}}}


class CurrentCorpusTests(unittest.TestCase):
    def test_future_cube_and_profiles_have_distinct_date_grids(self):
        from datetime import date
        body = load_request(FIXTURE)
        body.update(header(date(2026, 1, 15), .03, 8, 42, CURRENT))
        result = current_result()
        result.update(scenario_risk_available=True, measure="risk-neutral-pricing",
                      npv_cube=[[[0.0] * 4 for _ in range(3)] for _ in range(8)])
        result["precision"]["paths"] = 8
        times = [0.0, 30 / 365, 90 / 365, 180 / 365]
        def exposure(value):
            return {"times": times, "epe": [max(value, 0)] + [0] * 3, "ene": [max(-value, 0)] + [0] * 3}
        result["exposure"] = exposure(0)
        for row in result["trades"]:
            row["exposure"] = exposure(row["base_npv"])
        check_result(body, result)
        for mutate in (lambda r: r["npv_cube"][0].append([0.] * 4),
                       lambda r: r["exposure"]["epe"].__setitem__(0, 1),
                       lambda r: r["trades"][0]["exposure"].update(times=[])):
            with self.assertRaises(ValueError):
                bad = copy.deepcopy(result); mutate(bad); check_result(body, bad)

    def test_profile_identity_and_native_body_are_separate_from_legacy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            generate(root / "new", trades=100, profile=CURRENT)
            generate(root / "old", trades=100, profile=LEGACY)
            new, old = verify_manifest(root / "new"), verify_manifest(root / "old")
            self.assertEqual(new["engine_revision"], CURRENT_ENGINE)
            self.assertNotEqual(new["corpus_id"], old["corpus_id"])
            self.assertNotIn("schema_version", load_request(root / "new/portfolio-0000.json"))
            self.assertEqual(load_request(root / "old/portfolio-0000.json")["schema_version"], "2")
            new["engine_revision"] = old["engine_revision"]
            (root / "new/manifest.json").write_bytes(encoded(new))
            with self.assertRaises(ValueError):
                verify_manifest(root / "new")

    def test_wrong_rows_prices_precision_and_exposure_are_refused(self):
        body = load_request(FIXTURE)
        check_result(body, current_result())
        mutations = [lambda r: r.update(trades=[]), lambda r: r["trades"].reverse(),
                     lambda r: r["trades"][0].update(base_npv=1),
                     lambda r: r["trades"][0].update(base_npv=float("nan")),
                     lambda r: r.update(base_npv=2), lambda r: r.update(warnings=["unqualified"]),
                     lambda r: r["precision"].update(backend="tpu"),
                     lambda r: r["precision"]["policy"]["market"].update(compute="float32"),
                     lambda r: r.update(npv_cube=[1]), lambda r: r.update(scenario_risk_available=True)]
        for change in mutations:
            with self.subTest(change=change), self.assertRaises(ValueError):
                result = current_result(); change(result); check_result(body, result)


class CurrentClientTests(unittest.TestCase):
    setUp = legacy_tests.ClientTests.setUp
    tearDown = legacy_tests.ClientTests.tearDown

    def replies_for_success(self):
        content = content_sha256(FIXTURE.read_bytes())
        return [(200, {"execution_contract": CONTRACT, "numerical_engine_revision": CURRENT_ENGINE,
                       "request_content_digest": CONTENT_DIGEST_ALGORITHM}),
                (200, {"job_id": "fixture-job", "execution_contract": CONTRACT, "request_content_sha256": content}),
                (200, {"kind": "portfolio", "execution_contract": CONTRACT,
                       "request_content_sha256": content, "status": "done", "result": current_result()})]

    def run_current(self, name="current", **kwargs):
        self.submit = (202, {"job_id": "fixture-job", "execution_contract": CONTRACT,
                             "request_content_sha256": content_sha256(FIXTURE.read_bytes())})
        self.replies = self.replies_for_success()
        d = Driver(self.url, self.root / name, interval=.001)
        return d, d.run(FIXTURE, submission_id="current-golden", **kwargs)

    def test_current_routes_intake_and_offline_corruption_controls(self):
        d, result = self.run_current()
        self.assertEqual(result["outcome"], "DONE")
        self.assertEqual(self.gets, ["/portfolio/capabilities", "/portfolio/submissions/current-golden", "/jobs/fixture-job"])
        self.assertEqual(len(self.posts), 1)
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

    def test_current_unknown_contract_is_refused_before_post(self):
        self.replies = [(200, {"execution_contract": "other"})]
        result = Driver(self.url, self.root / "refused").run(FIXTURE, submission_id="key")
        self.assertEqual(result["outcome"], "INCOMPATIBLE_ENGINE")
        self.assertEqual(result["post_attempts"], 0)
        self.assertEqual(self.posts, [])

    def test_current_recovery_posts_nothing(self):
        # Recovery lookup plus the explicit binding witness, then the completed job.
        replies = self.replies_for_success()
        self.replies = replies[:2] + [replies[1]] + replies[2:]
        d = Driver(self.url, self.root / "recovered")
        result = d.run(FIXTURE, submission_id="current-golden", recover=True)
        self.assertEqual(result["outcome"], "DONE")
        self.assertEqual(result["post_attempts"], 0)
        self.assertEqual(self.posts, [])

    def test_current_binding_mismatch_retains_job_without_acceptance(self):
        self.submit = (202, {"job_id": "fixture-job", "execution_contract": CONTRACT,
                             "request_content_sha256": content_sha256(FIXTURE.read_bytes())})
        self.replies = self.replies_for_success()
        self.replies[1] = (200, {"job_id": "different", "execution_contract": CONTRACT})
        d = Driver(self.url, self.root / "mismatch")
        result = d.run(FIXTURE, submission_id="key")
        self.assertEqual(result["outcome"], "UNKNOWN")
        self.assertEqual(result["job_id"], "fixture-job")
        self.assertFalse((d.evidence / "acceptance.json").exists())

    def test_recovery_and_explicit_job_refuse_different_seed_or_samples(self):
        from datetime import date
        original = load_request(FIXTURE)
        original.update(header(date(2026, 1, 15), .03, 8, 42, CURRENT))
        old_hash = content_sha256(encoded(original))
        for field, value in (("seed", 43), ("samples", 16)):
            for recover in (True, False):
                bad = copy.deepcopy(original); bad["simulation"][field] = value
                request = self.root / f"{field}-{recover}.json"
                request.write_bytes(encoded(bad))
                self.replies = [(200, {"execution_contract": CONTRACT, "numerical_engine_revision": CURRENT_ENGINE,
                                       "request_content_digest": CONTENT_DIGEST_ALGORITHM}),
                                (200, {"job_id": "fixture-job", "execution_contract": CONTRACT,
                                       "request_content_sha256": old_hash})]
                d = Driver(self.url, self.root / f"refused-{field}-{recover}")
                outcome = d.run(request, submission_id="old-key", recover=recover,
                                job_id=None if recover else "fixture-job")
                self.assertEqual(outcome["outcome"], "REQUEST_MISMATCH")
                self.assertEqual(outcome["post_attempts"], 0)
                self.assertFalse((d.evidence / "acceptance.json").exists())
        self.assertEqual(self.posts, [])

    def test_content_digest_numeric_and_structural_equivalence(self):
        self.assertEqual(content_sha256(b'{"a":1,"b":-0.0}'), content_sha256(b'{ "b":0, "a":1.0 }'))
        self.assertNotEqual(content_sha256(b'{"a":true}'), content_sha256(b'{"a":1}'))
        self.assertNotEqual(content_sha256(b'{"a":0.1234567890123456789012345678901}'),
                            content_sha256(b'{"a":0.1234567890123456789012345678902}'))
        for raw in (b'{"a":NaN}', b'{"a":Infinity}', b'{"a":-Infinity}'):
            with self.assertRaises(ValueError):
                content_sha256(raw)

    def test_explicit_job_poll_requires_its_own_persisted_content_witness(self):
        self.replies = self.replies_for_success()
        self.replies[-1][1]["request_content_sha256"] = "different"
        d = Driver(self.url, self.root / "wrong-poll")
        result = d.run(FIXTURE, submission_id="current-golden", job_id="fixture-job")
        self.assertEqual(result["outcome"], "REQUEST_MISMATCH")
        self.assertEqual(result["post_attempts"], 0)
        self.assertEqual(self.posts, [])
        self.assertFalse((d.evidence / "acceptance.json").exists())


if __name__ == "__main__":
    unittest.main()
