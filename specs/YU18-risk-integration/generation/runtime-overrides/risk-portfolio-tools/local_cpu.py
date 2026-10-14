"""Real installed CPU service -> generated portfolios -> offline verified intake.

Only the process group this runner creates is stopped. Inputs, queue, results and
compilation cache remain in the new private evidence directory for review.
"""
import argparse
import copy
import hashlib
import importlib.metadata
import importlib.util
import json
import os
import signal
import socket
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from client import Driver
from corpus import digest, encoded, generate, load_request, verify_manifest
from intake import snapshot
from profiles import CONTRACT, CURRENT, CURRENT_ENGINE
from reference import analytic_reference


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def installed_identity(root, revision):
    if git(root, "rev-parse", "HEAD") != revision or git(root, "status", "--porcelain"):
        raise ValueError("require the exact committed clean engine candidate")
    git(root, "merge-base", "--is-ancestor", CURRENT_ENGINE, revision)
    allowed = {"engine/api/" + name for name in ("job_queue.py", "routes.py", "results.py", "worker.py", "service_identity.py")}
    changes = set(git(root, "diff", "--name-only", CURRENT_ENGINE, revision, "--", "engine").splitlines())
    if changes - allowed:
        raise ValueError("candidate changes numerical sources outside the selected upstream pin")
    package = Path(importlib.util.find_spec("engine").origin).parent
    if not package.is_relative_to(Path(sys.prefix)):
        raise ValueError("run with the candidate installed in the isolated virtual environment")
    files = git(root, "ls-tree", "-r", "--name-only", revision, "--", "engine").splitlines()
    records = {}
    for name in files:
        if not name.endswith((".py", ".json")):
            continue
        target = package / Path(name).relative_to("engine")
        expected = digest(root / name)
        if not target.is_file() or digest(target) != expected:
            raise ValueError("installed/source identity mismatch: " + name)
        records[name] = expected
    if not records:
        raise ValueError("empty installed source identity")
    return {"installed_package": str(package), "files": records,
            "sha256": hashlib.sha256(encoded(records)).hexdigest()}


def exchange(origin, path, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    request = urllib.request.Request(origin + path, data=encoded(body) if body is not None else None, headers=headers)
    try:
        response = urllib.request.urlopen(request, timeout=30)
    except urllib.error.HTTPError as exc:
        response = exc
    with response:
        return response.status, json.loads(response.read(16 * 1024 * 1024))


def stop(proc):
    if proc is None:
        return
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait(timeout=5)
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def main():
    if not __debug__:
        raise RuntimeError("acceptance assertions require Python without -O")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine-root", type=Path, required=True)
    parser.add_argument("--engine-revision", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    root = args.evidence.resolve()
    if any((p / ".git").exists() for p in (root, *root.parents)):
        raise ValueError("evidence must be outside Git checkouts")
    root.mkdir(parents=True, exist_ok=False)
    os.chmod(root, 0o700)
    report = {"status": "FAILED", "synthetic": True, "financial_validation": False,
              "numerical_engine_revision": CURRENT_ENGINE, "engine_revision": args.engine_revision,
              "scope": "direct synthetic USD Treasury portfolios; localhost installed CPU process; no sequenced export/container/cloud proof"}
    proc = None
    try:
        report["installed_identity"] = installed_identity(args.engine_root, args.engine_revision)
        report["dependencies"] = {p: importlib.metadata.version(p) for p in
                                  ("jax", "jaxlib", "open-source-risk-engine", "numpy", "scipy", "pydantic")}
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", JAX_PLATFORMS="cpu", PYTHONPATH="",
                   JAX_RISK_JOB_QUEUE=str(root / "jobs.sqlite"), JAX_RISK_WORKER="spawn",
                   JAX_COMPILATION_CACHE_DIR=str(root / "cache"), JAX_EOD_STORE_ROOT=str(root / "eod-unused"))
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        origin = f"http://127.0.0.1:{port}"
        report["origin"] = origin

        def launch():
            log = (root / "service.log").open("ab")
            try:
                child = subprocess.Popen([sys.executable, "-m", "uvicorn", "engine.api.app:app", "--host", "127.0.0.1",
                                          "--port", str(port)], cwd=root, env=env, stdout=log, stderr=log,
                                         start_new_session=True)
            finally:
                log.close()
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline and child.poll() is None:
                try:
                    code, caps = exchange(origin, "/portfolio/capabilities")
                    if code == 200 and caps.get("execution_contract") == CONTRACT:
                        return child
                except OSError:
                    pass
                time.sleep(.1)
            stop(child)
            raise RuntimeError("owned service failed startup; inspect service.log")

        proc = launch()
        if not (root / "eod-unused").is_dir():
            raise RuntimeError("unused EOD router did not initialize in its private run directory")
        requests = []
        golden = root / "golden.json"
        golden.write_bytes((Path(__file__).parent / "fixtures/usd-treasury-current.json").read_bytes())
        requests.append((golden, "current-golden"))
        for name, size, count, samples in (("repeated", 100, 2, 0), ("heterogeneous", 100, 2, 0),
                                           ("larger", 1000, 1, 0), ("exposure", 8, 1, 8)):
            corpus = root / name
            generate(corpus, seed=42, trades=size, portfolios=count, mode="repeated" if name == "repeated" else "heterogeneous",
                     samples=samples, profile=CURRENT)
            manifest = verify_manifest(corpus)
            requests.extend((corpus / item["path"], item["submission_id"]) for item in manifest["files"])
        report["jobs"] = []
        for index, (path, key) in enumerate(requests):
            print(json.dumps({"pricing": index, "trades": len(load_request(path)["trades"])}), flush=True)
            evidence = root / f"job-{index:02d}"
            result = Driver(origin, evidence, timeout=30, deadline=600, interval=.2).run(path, submission_id=key)
            if result["outcome"] != "DONE" or result["post_attempts"] != 1:
                raise RuntimeError(f"portfolio {index} did not complete: {result['outcome']}; {result.get('detail')}")
            checked = snapshot(evidence)
            checked["evidence"] = str(evidence)
            report["jobs"].append(checked)
        # Queue custody is an independent witness of the body the worker received.
        with sqlite3.connect(root / "jobs.sqlite") as queue:
            assert queue.execute("SELECT count(*) FROM jobs").fetchone()[0] == len(requests)
            for item, (path, _) in zip(report["jobs"], requests):
                raw, status, contract = queue.execute("SELECT request,status,execution_contract FROM jobs WHERE id=?", (item["job_id"],)).fetchone()
                assert bytes(raw) == path.read_bytes() and status == "done" and contract == CONTRACT
        # Exact queue survives API/worker restart; recovery is GET-only and same-job.
        stop(proc); proc = None
        proc = launch()
        recovered = Driver(origin, root / "recovered", deadline=60).run(golden, submission_id="current-golden", recover=True)
        assert recovered["outcome"] == "DONE" and recovered["post_attempts"] == 0
        assert recovered["job_id"] == report["jobs"][0]["job_id"]
        report["recovery"] = snapshot(root / "recovered")
        equivalent = load_request(golden)
        for trade in equivalent["trades"]:
            trade["face_amount"] = float(trade["face_amount"])
        equivalent_path = root / "equivalent-golden.json"
        equivalent_path.write_text(json.dumps(equivalent, indent=4) + "\n")
        normalized = Driver(origin, root / "normalized-recovery", deadline=60).run(
            equivalent_path, submission_id="current-golden", recover=True)
        assert normalized["outcome"] == "DONE" and normalized["post_attempts"] == 0
        assert normalized["job_id"] == report["jobs"][0]["job_id"]
        report["normalized_content_recovery"] = snapshot(root / "normalized-recovery")
        # Admission has a separate semantic/default-expanded identity. This
        # acknowledged protocol control must return the existing job, not run it again.
        explicit_defaults = load_request(golden)
        explicit_defaults["cube_output"] = "inline"
        code, same = exchange(origin, "/portfolio/price", explicit_defaults, "current-golden")
        assert code == 202 and same["job_id"] == report["jobs"][0]["job_id"]
        report["semantic_default_equivalence"] = {"http_status": code, "same_job": True}
        scenario_path, scenario_key = requests[-1]
        scenario_job = report["jobs"][-1]["job_id"]
        content_controls = []
        for field, value in (("seed", 43), ("samples", 16)):
            changed = load_request(scenario_path)
            changed["simulation"][field] = value
            request_path = root / f"changed-{field}.json"
            request_path.write_bytes(encoded(changed))
            for recover in (True, False):
                evidence = root / f"refused-{field}-{recover}"
                state = Driver(origin, evidence, deadline=60).run(
                    request_path, submission_id=scenario_key, recover=recover,
                    job_id=None if recover else scenario_job)
                assert state["outcome"] == "REQUEST_MISMATCH" and state["post_attempts"] == 0
                assert all(event["method"] == "GET" for event in state["events"])
                assert not (evidence / "acceptance.json").exists()
                content_controls.append({"field": field, "recover": recover, "outcome": state["outcome"],
                                         "post_attempts": 0, "evidence": str(evidence)})
        report["content_binding_controls"] = content_controls
        # Exit after the real POST reply, before recording its job ID. Recovery
        # must reconcile the persisted submission intent without another POST.
        fault = root / "fault-consumer.py"
        fault.write_text('''import os, sys
sys.dont_write_bytecode = True
sys.path.insert(0, sys.argv[1])
from client import Driver
from pathlib import Path
d = Driver(sys.argv[2], Path(sys.argv[3]), deadline=120)
exchange = d.exchange
def lose_reply(method, *args, **kwargs):
    response = exchange(method, *args, **kwargs)
    if method == "POST":
        os._exit(75)
    return response
d.exchange = lose_reply
d.run(Path(sys.argv[4]), submission_id="current-lost-reply")
''')
        interrupted = root / "interrupted-consumer"
        child = subprocess.run([sys.executable, str(fault), str(Path(__file__).parent), origin,
                                str(interrupted), str(golden)], cwd=root, env=env, timeout=120)
        assert child.returncode == 75
        intent = json.loads((interrupted / "receipt.json").read_bytes())
        assert intent["post_attempts"] == 1 and intent["outcome"] == "SUBMISSION_UNKNOWN" and not intent.get("job_id")
        resumed = Driver(origin, root / "lost-reply-recovered", deadline=120).run(
            golden, submission_id="current-lost-reply", recover=True)
        assert resumed["outcome"] == "DONE" and resumed["post_attempts"] == 0
        report["lost_reply_recovery"] = snapshot(root / "lost-reply-recovered")
        report["lost_reply_recovery"]["consumer_exit"] = child.returncode
        # The HTTP result exposes dirty NPV; check accrued values separately.
        from engine.api.requests import MarketPortfolioRequestSchema
        from engine.instruments.treasury import accrued_interest
        native = MarketPortfolioRequestSchema.model_validate(load_request(golden)).to_dataclass()
        actual = [float(accrued_interest(trade)) for trade in native.trades]
        expected = analytic_reference(load_request(golden))["accrued_usd"]
        import math
        assert all(math.isclose(a, b, rel_tol=1e-10, abs_tol=1e-6) for a, b in zip(actual, expected))
        report["direct_accrued_usd"] = actual
        # A conflict and unsupported terms must create no additional work.
        changed = load_request(golden)
        changed["trades"][0]["face_amount"] *= 2
        code, _ = exchange(origin, "/portfolio/price", changed, "current-golden")
        assert code == 409
        controls = {"submission_conflict": code}
        for name, mutate, expected in (
            ("unknown_field", lambda b: b.update(schema_version="2"), 422),
            ("unsupported_product", lambda b: b["trades"][0].update(trade_type="equity"), 422),
            ("duplicate_trade", lambda b: b["trades"][1].update(trade_id=b["trades"][0]["trade_id"]), 400),
            ("missing_market", lambda b: b["market"].update(currencies={}), 400)):
            bad = load_request(golden); mutate(bad)
            code, _ = exchange(origin, "/portfolio/price", bad, "negative-" + name)
            assert code == expected, (name, code)
            controls[name] = code
        with sqlite3.connect(root / "jobs.sqlite") as queue:
            assert queue.execute("SELECT count(*) FROM jobs").fetchone()[0] == len(requests) + 1
        report["negative_controls"] = controls
        assert not any(p.is_file() for p in (root / "eod-unused").rglob("*"))
        report["unused_eod_store_isolated_and_empty"] = True
        stop(proc); proc = None
        # Intake reads and revalidates with no live service. Corruption must fail.
        for item in report["jobs"]:
            snapshot(Path(item["evidence"]))
        # Rewrite request and local metadata in a disposable copy: immutable HTTP
        # witnesses must still expose the mismatch, independently of the oracle.
        import shutil
        from request_identity import content_sha256
        mismatch = root / "offline-request-mismatch"
        shutil.copytree(root / "job-06", mismatch)
        changed = load_request(mismatch / "request.json")
        changed["simulation"]["seed"] = 43
        (mismatch / "request.json").write_bytes(encoded(changed))
        acceptance = json.loads((mismatch / "acceptance.json").read_bytes())
        acceptance["files"]["request.json"] = digest(mismatch / "request.json")
        acceptance["request_content_sha256"] = content_sha256((mismatch / "request.json").read_bytes())
        (mismatch / "acceptance.json").write_bytes(encoded(acceptance))
        try:
            snapshot(mismatch)
        except ValueError:
            report["offline_request_binding_refused"] = True
        else:
            raise AssertionError("wrong request content passed offline witnesses")
        corrupt = root / "job-00/result.json"
        original = corrupt.read_bytes(); corrupt.write_bytes(original + b" ")
        try:
            snapshot(corrupt.parent)
        except ValueError:
            report["offline_corruption_refused"] = True
        else:
            raise AssertionError("corrupt custody passed")
        finally:
            corrupt.write_bytes(original)
        report["status"] = "PASSED"
    finally:
        stop(proc)
        report["owned_process_group_stopped"] = True
        (root / "report.json").write_bytes(encoded(report) + b"\n")
    print(json.dumps({"status": report["status"], "jobs": len(report["jobs"]), "evidence": str(root / "report.json")}))


if __name__ == "__main__":
    main()
