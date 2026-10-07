"""Opt-in real CPU HTTP/worker proof in a private archive, never a retained rig.

Use the engine dependency environment and a committed engine revision. Both API and
worker are owned by this script and stopped on every exit. No dependency installation.
"""
import argparse
import io
import json
import os
import signal
import socket
import subprocess
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

from client import Driver
from corpus import ENGINE


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--engine-root", type=Path, required=True)
    p.add_argument("--engine-revision", default=ENGINE)
    p.add_argument("--evidence", type=Path, required=True)
    p.add_argument("--identity-recovery", action="store_true", help="requires committed RI16 contract")
    args = p.parse_args()
    revision = subprocess.check_output(["git", "-C", str(args.engine_root), "rev-parse", args.engine_revision], text=True).strip()
    if subprocess.call(["git", "-C", str(args.engine_root), "merge-base", "--is-ancestor", ENGINE, revision]):
        raise SystemExit("engine revision must descend from pinned schema baseline")
    args.evidence.mkdir(parents=True, exist_ok=False)
    source = args.evidence / "engine-source"
    source.mkdir()
    archive = subprocess.check_output(["git", "-C", str(args.engine_root), "archive", revision])
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        tar.extractall(source, filter="data")
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", JAX_PLATFORMS="cpu",
               JAX_RISK_JOB_QUEUE=str(args.evidence.resolve() / "jobs.sqlite"),
               JAX_RISK_WORKER="spawn", JAX_COMPILATION_CACHE_DIR=str(args.evidence.resolve() / "cache"))
    # Bound archive/compilation artifacts to this private proof directory.
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    origin = f"http://127.0.0.1:{port}"
    proc = None
    report = {"synthetic": True, "engine_revision": revision, "baseline_schema": ENGINE,
              "origin": origin, "scope": "four synthetic USD positions; CPU localhost; no scale/production claim"}
    try:
        with (args.evidence / "service.log").open("wb") as log:
            proc = subprocess.Popen([sys.executable, "-m", "uvicorn", "engine.api.app:app", "--host", "127.0.0.1",
                                     "--port", str(port)], cwd=source, env=env, stdout=log, stderr=log,
                                    start_new_session=True)
            until = time.monotonic() + 45
            ready = False
            while time.monotonic() < until and proc.poll() is None:
                try:
                    with urllib.request.urlopen(origin + "/health", timeout=1) as reply:
                        ready = json.load(reply).get("status") == "ok"
                    if ready:
                        break
                except OSError:
                    pass
                time.sleep(.1)
            if not ready:
                raise RuntimeError("owned API failed startup; inspect service.log")
            fixture = Path(__file__).parent / "fixtures/usd-treasury-golden.json"
            key = "synthetic-ri14-golden-v1" if args.identity_recovery else None
            first = Driver(origin, args.evidence / "submit", deadline=120).run(fixture, submission_id=key)
            report["submit_outcome"] = first["outcome"]
            if first["outcome"] != "DONE":
                raise RuntimeError("real service did not complete selected golden: " + first["outcome"])
            if args.identity_recovery:
                recovered = Driver(origin, args.evidence / "recover", deadline=30).run(fixture, submission_id=key, recover=True)
                report["recovery_outcome"] = recovered["outcome"]
                if recovered["outcome"] != "DONE" or recovered["job_id"] != first["job_id"] or recovered["post_attempts"] != 0:
                    raise RuntimeError("real identity recovery failed")
    finally:
        if proc:
            if proc.poll() is None:
                proc.terminate()  # Uvicorn lifespan calls shutdown_worker.
                try:
                    proc.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait(timeout=5)
            # Ensure no child in this owned process group remains after API exit.
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            report.update(api_exit_code=proc.returncode, owned_process_group_stopped=True)
        (args.evidence / "report.json").write_text(json.dumps(report, sort_keys=True, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
