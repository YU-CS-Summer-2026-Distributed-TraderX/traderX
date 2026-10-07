"""Actual runner + supervisor + disposable fake CLI integration acceptance."""
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[3]
HELPER = ROOT / "scripts/yu15/proof-cleanup.py"
FAKE = Path(__file__).with_name("fake-command.py")
BASE_IMAGE = "offline/base@sha256:" + hashlib.sha256(b"fake-base").hexdigest()
PRE_IMAGE = "offline/pre@sha256:" + hashlib.sha256(b"fake-pre").hexdigest()
FIX_IMAGE = "offline/fix@sha256:" + hashlib.sha256(b"fake-fix").hexdigest()


class CleanupAcceptance(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="ri17-")
        self.base = Path(self.tmp.name)
        self.rig = self.base / "rig"
        self.rig.mkdir()
        self.tree = self.base / "tree"
        (self.tree / "scripts/yu15").mkdir(parents=True)
        (self.tree / "scripts/proofs").mkdir()
        (self.tree / "specs/YU18-risk-integration").mkdir(parents=True)
        for name in ("run-proofs.sh", "proof-cleanup.py", "lib-state-image.sh", "lib-replay-epoch.sh", "replay-anchor-evidence.py", "image-admission.py"):
            shutil.copy(ROOT / "scripts/yu15" / name, self.tree / "scripts/yu15" / name)
        # Proof bodies are controlled fixture processes. Suite lifecycle is unmodified.
        (self.tree / "scripts/yu15/seed-proof-fixtures.sh").write_text("#!/bin/bash\necho '68 instruments enabled'\n")
        (self.tree / "scripts/proofs/yu13-stp-and-replace.sh").write_text('''#!/bin/bash
GW_HISTORICAL_PROBES='"startupProbe":null,"livenessProbe":null,"readinessProbe":{"httpGet":{"path":"/ready","port":18110}}'
kubectl --context "$CTX" -n "$NS" set image sts/order-matcher-cluster "node=$IMAGE_FIX"
kubectl --context "$CTX" -n "$NS" scale deploy/price-publisher --replicas=0
touch "$FAKE_RIG/proof-entered"
case "$FAKE_SCENARIO" in
  hold-proof) exec python3 -c 'import time; time.sleep(60)' ;;
  proof-failure) exit 19 ;;
esac
exit 0
''')
        self.bin = self.base / "bin"
        self.bin.mkdir()
        for name in ("kubectl", "curl", "docker", "kind", "sleep", "pkill"):
            path = self.bin / name
            shutil.copy(FAKE, path)
            path.chmod(0o755)
        objects = {}
        for i, name in enumerate(["sts/order-matcher-cluster", "deploy/cluster-gateway", "deploy/feed-adapter",
                                  "deploy/risk-extract", "deploy/execution-algo-engine", "deploy/price-publisher",
                                  "deploy/grafana", "deploy/loki", "deploy/tempo", "deploy/prometheus", "deploy/otel-collector",
                                  "deploy/trade-processor"]):
            c = {"name": "cluster-node" if name == "sts/order-matcher-cluster" else "node", "image": BASE_IMAGE}
            if name == "sts/order-matcher-cluster": c["env"] = [{"name":"CLUSTER_BASE_DIR", "value":"/data"}]
            if name == "deploy/cluster-gateway":
                # Intentionally absent startup probe and CONTROL_FEED_SUBSCRIBER. A valueFrom
                # env entry and a custom readiness probe must survive verbatim.
                c.update(env=[{"name": "CUSTOM", "valueFrom": {"configMapKeyRef": {"name": "custom", "key": "v"}}}],
                         readinessProbe={"httpGet": {"path": "/custom", "port": 5555}})
            objects[name] = {"kind":"StatefulSet" if name.startswith("sts/") else "Deployment", "metadata": {"name":name.split("/",1)[1], "namespace":"traderx", "uid": "uid-" + str(i)}, "spec": {"replicas": 2 + i % 3,
                            "template": {"metadata": {}, "spec": {"containers": [c]}}}}
        objects["sts/order-matcher-cluster"]["spec"].update(
            volumeClaimTemplates=[{"metadata": {"name": "data"}}], selector={"matchLabels": {"app": "order-matcher-cluster"}})
        objects["sts/order-matcher-cluster"]["spec"]["template"]["spec"]["containers"][0]["volumeMounts"] = [{"name": "data", "mountPath": "/data"}]
        self.initial = {"server": "https://offline-one", "namespaceUID": "namespace-one", "objects": objects}
        self.write(self.initial)
        self.journal = self.base / "journal.json"
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        FAKE_RIG=str(self.rig), FAKE_SCENARIO="success", CLUSTER_IMAGE=BASE_IMAGE,
                        IMAGE_PRE=PRE_IMAGE, IMAGE_FIX=FIX_IMAGE,
                        FAKE_COMPATIBILITY="compatible", PROOF_RETAINED_COMPATIBILITY=str(self.base / "review.json"),
                        CTX="kind-offline", NS="traderx", PROOF_LOG_DIR=str(self.base / "proof-logs"), PROOF_CLEANUP_JOURNAL=str(self.journal), ALLOW_PROOF_RESET="1")
        self.processes = []

    def tearDown(self):
        for p in self.processes:
            if p.poll() is None:
                p.kill()
                p.wait()
            if p.stdout:
                p.stdout.close()
        self.tmp.cleanup()

    def write(self, value):
        (self.rig / "state.json").write_text(json.dumps(value))

    def state(self):
        return json.loads((self.rig / "state.json").read_text())

    def run_runner(self, scenario="success", background=False, selection=None):
        env = dict(self.env, FAKE_SCENARIO=scenario)
        cmd = ["bash", str(self.tree / "scripts/yu15/run-proofs.sh"), *(selection or ["yu13-stp-and-replace"])]
        if background:
            p = subprocess.Popen(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            self.processes.append(p)
            return p
        return subprocess.run(cmd, env=env, text=True, capture_output=True, timeout=25)

    def recover(self, **updates):
        env = dict(self.env, FAKE_SCENARIO="success", **updates)
        return subprocess.run(["python3", str(HELPER), "recover"], env=env, text=True, capture_output=True, timeout=25)

    def wait_file(self, name):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if (self.rig / name).exists():
                return
            time.sleep(.02)
        self.fail("fixture did not reach " + name)

    def assert_restored(self):
        # Independent expected state is the complete original fixture, not helper.view().
        self.assertEqual(self.initial, self.state())
        self.assertEqual("complete", json.loads(self.journal.read_text())["phase"])

    def test_success_exact_state_absent_keys_and_nondefault_replicas(self):
        p = self.run_runner()
        self.assertEqual(0, p.returncode, p.stdout + p.stderr)
        self.assertIn("proof_exit=0 cleanup_exit=0", p.stdout)
        self.assert_restored()
        j = json.loads(self.journal.read_text())
        self.assertTrue(j["irreversible"])
        self.assertEqual(0o600, self.journal.stat().st_mode & 0o777)
        calls = [json.loads(x) for x in (self.rig / "calls.jsonl").read_text().splitlines()]
        self.assertEqual(1, sum(c[0] == "delete" for c in calls), "cleanup must never delete storage")

    def test_existing_custom_env_and_probes_are_restored_verbatim(self):
        c = self.initial["objects"]["deploy/cluster-gateway"]["spec"]["template"]["spec"]["containers"][0]
        c["env"].append({"name": "CONTROL_FEED_SUBSCRIBER", "value": "custom-original"})
        c["startupProbe"] = {"exec": {"command": ["custom-start"]}, "failureThreshold": 7}
        c["livenessProbe"] = {"httpGet": {"path": "/custom-live", "port": 4567}, "periodSeconds": 13}
        self.write(self.initial)
        p = self.run_runner()
        self.assertEqual(0, p.returncode, p.stdout + p.stderr)
        self.assert_restored()

    def test_failed_preparation_restores_partially_applied_mutation(self):
        p = self.run_runner("prep-failure")
        self.assertEqual(1, p.returncode, p.stdout + p.stderr)
        self.assertFalse((self.rig / "proof-entered").exists())
        self.assert_restored()

    def test_failed_proof_keeps_failure_after_cleanup(self):
        p = self.run_runner("proof-failure")
        self.assertEqual(1, p.returncode, p.stdout + p.stderr)
        self.assertTrue((self.rig / "proof-entered").exists(), p.stdout + p.stderr)
        self.assertIn("proof_exit=1 cleanup_exit=0", p.stdout)
        self.assert_restored()

    def fail_forwards_after_gateway_return(self):
        # Inject the coordinator's actual curl failure condition. POST seeding still succeeds.
        (self.bin / "curl").write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
root=Path(os.environ['FAKE_RIG'])
for line in (root/'calls.jsonl').read_text().splitlines():
 c=json.loads(line)
 if c[0]=='patch' and 'cluster-gateway' in ' '.join(c) and '-p' in c:
  patch=json.loads(c[c.index('-p')+1])
  if isinstance(patch,dict):
   cs=patch.get('spec',{}).get('template',{}).get('spec',{}).get('containers',[])
   if any(x.get('image')==os.environ['CLUSTER_IMAGE'] for x in cs):
    print('000');sys.exit(7)
print('{"seeded":true}' if 'POST' in sys.argv[1:] else '200')
''')

    def test_single_selected_proof_prerequisite_failure_is_terminal_failure(self):
        self.fail_forwards_after_gateway_return()
        p = self.run_runner()
        self.assertEqual(1, p.returncode, p.stdout + p.stderr)
        self.assertFalse((self.rig / "proof-entered").exists())
        self.assertIn("0 executed, 1 terminal outcomes", p.stdout)
        self.assertIn("proof_exit=1 cleanup_exit=0", p.stdout)
        self.assertNotIn("RUN COMPLETE", p.stdout)
        self.assert_restored()

    def test_final_selected_proof_prerequisite_failure_preserves_prior_execution(self):
        (self.tree / "scripts/proofs/yu03-risk-proof.sh").write_text(
            '#!/bin/bash\ntouch "$FAKE_RIG/first-proof-entered"\nexit 0\n')
        self.fail_forwards_after_gateway_return()
        p = self.run_runner(selection=["yu03-risk-proof", "yu13-stp-and-replace"])
        self.assertEqual(1, p.returncode, p.stdout + p.stderr)
        self.assertTrue((self.rig / "first-proof-entered").exists())
        self.assertFalse((self.rig / "proof-entered").exists())
        self.assertIn("1 succeeded, 0 skipped, 1 failed", p.stdout)
        self.assertIn("1 executed, 2 terminal outcomes", p.stdout)
        self.assertIn("proof_exit=1 cleanup_exit=0", p.stdout)
        self.assertNotIn("RUN COMPLETE", p.stdout)
        self.assert_restored()

    def test_term_and_int_restore(self):
        for sig in (signal.SIGTERM, signal.SIGINT):
            with self.subTest(sig=sig):
                (self.rig / "proof-entered").unlink(missing_ok=True)
                p = self.run_runner("hold-proof", background=True)
                self.wait_file("proof-entered")
                p.send_signal(sig)
                out, _ = p.communicate(timeout=20)
                self.assertEqual(128 + sig, p.returncode, out)
                self.assert_restored()

    def test_cleanup_failure_retains_journal_and_refuses_new_run(self):
        p = self.run_runner("cleanup-failure")
        self.assertEqual(70, p.returncode, p.stdout + p.stderr)
        self.assertIn("proof_exit=0 cleanup_exit=1", p.stdout)
        self.assertNotIn("PASS", p.stdout)
        self.assertNotEqual(self.initial, self.state())
        self.assertEqual(["deploy/grafana"], json.loads(self.journal.read_text())["pendingResources"])
        refused = self.run_runner()
        self.assertEqual(70, refused.returncode)
        self.assertIn("unfinished", refused.stderr)
        fixed = self.recover()
        self.assertEqual(0, fixed.returncode, fixed.stderr)
        self.assert_restored()
        before = (self.rig / "calls.jsonl").read_text()
        self.assertEqual(0, self.recover().returncode)
        later = (self.rig / "calls.jsonl").read_text()[len(before):]
        self.assertFalse(any(json.loads(c)[0] in ("patch", "scale", "delete") for c in later.splitlines()))

    def test_sigkill_leaves_unfinished_and_wrong_context_refuses(self):
        p = self.run_runner("hold-proof", background=True)
        self.wait_file("proof-entered")
        p.kill()
        p.wait(timeout=10)
        before = self.state()
        active = self.recover()
        self.assertEqual(70, active.returncode)
        self.assertIn("owns this journal", active.stderr)
        self.assertEqual(before, self.state())
        # SIGKILL cannot reap descendants. The test owns the fixture's child group and stops it
        # explicitly before recovery, as an operator must after host/supervisor loss.
        self.stop_orphan_group()
        self.assertEqual(70, self.run_runner().returncode)
        before = self.state()
        wrong = self.recover(CTX="kind-other")
        self.assertEqual(70, wrong.returncode, wrong.stdout + wrong.stderr)
        self.assertEqual(before, self.state())
        self.assertEqual(0, self.recover().returncode)
        self.assert_restored()

    def stop_orphan_group(self):
        # runner PID is recorded in journal by the supervisor; never global pkill.
        j = json.loads(self.journal.read_text())
        os.killpg(j["runnerPID"], signal.SIGKILL)

    def test_interruption_during_cleanup_is_recoverable(self):
        p = self.run_runner("hold-cleanup", background=True)
        self.wait_file("cleanup-held")
        p.kill()
        p.wait(timeout=10)
        before = self.state()
        active = self.recover()
        self.assertEqual(70, active.returncode)
        self.assertIn("owns this journal", active.stderr)
        self.assertEqual(before, self.state())
        # Fake cleanup kubectl owns the inherited lease. Fixture exports its exact PID.
        self.stop_cleanup_command()
        self.assertEqual(70, self.run_runner().returncode)
        recovered = self.recover()
        self.assertEqual(0, recovered.returncode, recovered.stdout + recovered.stderr)
        self.assert_restored()

    def test_term_during_cleanup_retains_intent_and_reports_signal(self):
        p = self.run_runner("hold-cleanup", background=True)
        self.wait_file("cleanup-held")
        p.send_signal(signal.SIGTERM)
        time.sleep(.05)
        self.stop_cleanup_command()
        out, _ = p.communicate(timeout=15)
        self.assertEqual(143, p.returncode, out)
        self.assertEqual("unfinished", json.loads(self.journal.read_text())["phase"])
        self.assertEqual(0, self.recover().returncode)
        self.assert_restored()

    def test_absent_optional_deployment_stays_absent(self):
        self.initial["objects"].pop("deploy/prometheus")
        self.write(self.initial)
        p = self.run_runner()
        self.assertEqual(0, p.returncode, p.stdout + p.stderr)
        self.assert_restored()

    def test_failed_member_readiness_keeps_clients_quiet_and_requires_recovery(self):
        s = self.state()
        s["unready"] = True
        self.write(s)
        p = self.run_runner()
        self.assertEqual(70, p.returncode, p.stdout + p.stderr)
        self.assertIn("original members have not all returned", p.stderr)
        self.assertEqual(0, self.state()["objects"]["deploy/cluster-gateway"]["spec"]["replicas"])
        self.assertEqual(70, self.recover().returncode, "retry must recheck actual member readiness")
        s = self.state()
        s.pop("unready")
        self.write(s)
        self.assertEqual(0, self.recover().returncode)
        self.assert_restored()

    def test_baseline_interruption_leaves_actual_preparation_stranded(self):
        baseline = subprocess.check_output(["git", "show", "a0d6da0b:scripts/yu15/run-proofs.sh"], cwd=ROOT, text=True)
        (self.tree / "scripts/yu15/run-proofs.sh").write_text(baseline)
        p = subprocess.Popen(["bash", str(self.tree / "scripts/yu15/run-proofs.sh"), "yu13-stp-and-replace"],
                             env=dict(self.env, FAKE_SCENARIO="hold-proof"), start_new_session=True,
                             text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.processes.append(p)
        try:
            self.wait_file("proof-entered")
        finally:
            os.killpg(p.pid, signal.SIGTERM)
        out, _ = p.communicate(timeout=15)
        self.assertNotEqual(self.initial, self.state(), out)
        objects = self.state()["objects"]
        self.assertEqual(0, objects["deploy/grafana"]["spec"]["replicas"])
        self.assertEqual(0, objects["deploy/feed-adapter"]["spec"]["replicas"])
        self.assertEqual(FIX_IMAGE, objects["sts/order-matcher-cluster"]["spec"]["template"]["spec"]["containers"][0]["image"])
        self.assertFalse(self.journal.exists(), "baseline records no durable unfinished intent")

    def stop_cleanup_command(self):
        os.kill(int((self.rig / "cleanup-held").read_text()), signal.SIGKILL)

    def test_target_identity_and_object_replacement_refuse_all_writes(self):
        self.run_runner("cleanup-failure")
        for key, value in (("server", "https://offline-two"), ("namespaceUID", "namespace-two")):
            s = self.state()
            s[key] = value
            self.write(s)
            before = copy.deepcopy(s)
            self.assertEqual(70, self.recover().returncode)
            self.assertEqual(before, self.state())
            s[key] = self.initial[key]
            self.write(s)
        s = self.state()
        s["objects"]["deploy/loki"]["metadata"]["uid"] = "replacement"
        self.write(s)
        self.assertEqual(70, self.recover().returncode)
        self.assertEqual(s, self.state())

    def test_reset_requires_explicit_authorization(self):
        self.env.pop("ALLOW_PROOF_RESET")
        p = self.run_runner()
        self.assertEqual(1, p.returncode, p.stdout + p.stderr)
        self.assertIn("ALLOW_PROOF_RESET=1", p.stderr)
        self.assert_restored()
        self.assertFalse(any(json.loads(c)[0] == "delete" for c in (self.rig / "calls.jsonl").read_text().splitlines()))

    def test_emptydir_backing_refuses_before_member_stop_and_restores_other_prep(self):
        self.initial["objects"]["sts/order-matcher-cluster"]["spec"].pop("volumeClaimTemplates")
        self.write(self.initial)
        p = self.run_runner()
        self.assertEqual(1, p.returncode, p.stdout + p.stderr)
        self.assertIn("lacks PVC backing", p.stderr)
        self.assert_restored()
        calls = [json.loads(c) for c in (self.rig / "calls.jsonl").read_text().splitlines()]
        self.assertFalse(any(c[0] == "delete" or (c[0] == "scale" and c[1] in ("sts", "sts/order-matcher-cluster")) for c in calls))

    def test_scale_delete_retention_refuses_before_storage_mutation(self):
        self.initial["objects"]["sts/order-matcher-cluster"]["spec"]["persistentVolumeClaimRetentionPolicy"] = {"whenScaled": "Delete"}
        self.write(self.initial)
        p = self.run_runner()
        self.assertEqual(1, p.returncode, p.stdout + p.stderr)
        self.assertIn("deleted on scale-down", p.stderr)
        self.assert_restored()
        self.assertFalse(any(json.loads(c)[0] == "delete" for c in (self.rig / "calls.jsonl").read_text().splitlines()))

    def assert_member_rollback_refused(self, result, expected_status=70):
        self.assertEqual(expected_status, result.returncode, result.stdout + result.stderr)
        j = json.loads(self.journal.read_text())
        self.assertEqual("unfinished", j["phase"])
        self.assertEqual(1, j["cleanupExit"])
        self.assertIn("sts/order-matcher-cluster", j["pendingResources"])
        calls = [json.loads(c) for c in (self.rig / "calls.jsonl").read_text().splitlines()]
        self.assertFalse(any(c[0] == "patch" and c[1] == "sts/order-matcher-cluster" for c in calls),
                         "no cleanup stop, image patch or restart before compatibility")
        objects = self.state()["objects"]
        for name in ("cluster-gateway", "feed-adapter", "execution-algo-engine", "price-publisher", "risk-extract", "trade-processor"):
            self.assertEqual(0, objects["deploy/" + name]["spec"]["replicas"])
        for name in ("grafana", "loki", "tempo", "prometheus", "otel-collector"):
            self.assertEqual(self.initial["objects"]["deploy/" + name], objects["deploy/" + name],
                             "independent observability configuration can still be restored")

    def test_missing_compatibility_repeated_recovery_refuses_until_review_supplied(self):
        self.env["FAKE_COMPATIBILITY"] = "missing"
        p = self.run_runner()
        self.assert_member_rollback_refused(p)
        self.assertIn("separately reviewed retained compatibility", p.stderr)
        self.assertEqual(FIX_IMAGE, self.state()["objects"]["sts/order-matcher-cluster"]["spec"]["template"]["spec"]["containers"][0]["image"])
        again = self.recover()
        self.assert_member_rollback_refused(again)
        self.assertEqual(0, self.recover(FAKE_COMPATIBILITY="compatible").returncode)
        self.assert_restored()

    def test_unknown_original_image_is_not_restored_after_failed_preparation(self):
        self.initial["objects"]["sts/order-matcher-cluster"]["spec"]["template"]["spec"]["containers"][0]["image"] = "traderx/cluster-node:unverified-old-build"
        self.write(self.initial)
        self.env["ALLOW_IMAGE_CHANGE"] = "1"
        p = self.run_runner()
        self.assert_member_rollback_refused(p, expected_status=1)
        self.assertIn("mutable/unknown member image identity", p.stderr)
        self.assertFalse((self.rig / "proof-entered").exists())
        self.assertEqual(BASE_IMAGE, self.state()["objects"]["sts/order-matcher-cluster"]["spec"]["template"]["spec"]["containers"][0]["image"])
        self.assert_member_rollback_refused(self.recover())

    def test_incompatible_retained_evidence_refuses_despite_ready_pods(self):
        self.env["FAKE_COMPATIBILITY"] = "incompatible"
        p = self.run_runner()
        self.assert_member_rollback_refused(p)
        self.assertIn("failed, missing or duplicate checks", p.stderr)

    def test_readiness_only_evidence_cannot_authorize_retained_rollback(self):
        self.env["FAKE_COMPATIBILITY"] = "readiness-only"
        p = self.run_runner()
        self.assert_member_rollback_refused(p)
        self.assertIn("failed, missing or duplicate checks", p.stderr)

    def test_mismatched_boundary_identity_hash_or_empty_proof_refuses(self):
        for mode in ("boundary-mismatch", "identity-mismatch", "hash-mismatch", "zero-assertions", "incomplete-writers", "malformed-review", "malformed-checks"):
            with self.subTest(mode=mode):
                # Every arm uses a fresh original fixture and journal.
                self.write(self.initial)
                self.journal.unlink(missing_ok=True)
                (self.rig / "calls.jsonl").unlink(missing_ok=True)
                self.env["FAKE_COMPATIBILITY"] = mode
                p = self.run_runner()
                self.assert_member_rollback_refused(p)

    def test_reviewed_compatible_fixture_records_exact_boundary_and_evidence(self):
        p = self.run_runner()
        self.assertEqual(0, p.returncode, p.stdout + p.stderr)
        self.assert_restored()
        j = json.loads(self.journal.read_text())
        self.assertEqual([BASE_IMAGE, FIX_IMAGE, PRE_IMAGE], j["requiredCompatibility"]["writerImages"])
        self.assertEqual("offline-fixture-reviewer", j["compatibilityEvidence"]["reviewedBy"])
        self.assertEqual(3, len(j["requiredCompatibility"]["pvcBoundary"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
