"""Offline verified custody for direct synthetic portfolios, separate from EOD intake.

Hashes detect accidental corruption, not malicious rewriting by a storage owner.
No account authorization or sequenced TraderX export lineage is implied.
"""
import json
import os
from pathlib import Path

from corpus import digest, encoded, load_request
from profiles import CONTRACT, scenario
from reference import check_result, request_binding


def read_json(path, limit=16 * 1024 * 1024):
    with Path(path).open("rb") as stream:
        raw = stream.read(limit + 1)
    if len(raw) > limit:
        raise ValueError("custody document exceeds byte budget")
    return json.loads(raw)


def publish(root, state, result):
    root = Path(root)
    records = {"request.json": digest(root / "request.json")}
    for event in state["events"]:
        if event.get("sha256"):
            records[event["file"]] = event["sha256"]
    output = root / "result.json"
    with output.open("xb") as stream:
        stream.write(encoded(result) + b"\n")
        stream.flush()
        os.fsync(stream.fileno())
    records["result.json"] = digest(output)
    acceptance = {"version": 1, "synthetic": True, "financial_validation": False,
                  "provenance": "direct-synthetic-portfolio; no sequenced TraderX export lineage",
                  "job_id": state["job_id"], "submission_id": state["submission_id"],
                  "execution_contract": CONTRACT, "files": records,
                  "events": state["events"], "checks": state["result_checks"]}
    # Publication is the final step; incomplete result bytes alone are never accepted.
    tmp = root / "acceptance.pending"
    with tmp.open("xb") as stream:
        stream.write(encoded(acceptance) + b"\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.link(tmp, root / "acceptance.json")  # atomic create-only publication
    tmp.unlink()
    return {"file": "acceptance.json", "sha256": digest(root / "acceptance.json")}


def snapshot(root):
    root = Path(root)
    acceptance = read_json(root / "acceptance.json", 1024 * 1024)
    if acceptance.get("execution_contract") != CONTRACT or acceptance.get("synthetic") is not True:
        raise ValueError("unknown accepted portfolio contract")
    if acceptance.get("financial_validation") is not False or not acceptance.get("submission_id") or not acceptance.get("job_id"):
        raise ValueError("invalid acceptance scope/identity")
    files = acceptance["files"]
    if not {"request.json", "result.json"} <= set(files):
        raise ValueError("missing custody files")
    total = 0
    for name, expected in files.items():
        p = root / name
        if Path(name).name != name or p.is_symlink() or digest(p) != expected:
            raise ValueError("custody checksum/path mismatch")
        if name not in ("request.json", "result.json"):
            total += p.stat().st_size
    if total > 128 * 1024 * 1024:
        raise ValueError("raw replies exceed evidence byte budget")
    body = load_request(root / "request.json")
    if body["idempotency_key"] != acceptance["submission_id"]:
        raise ValueError("accepted submission identity is not the request's idempotency_key")
    result = read_json(root / "result.json")
    run = acceptance["job_id"]
    bound = completed = False
    for event in acceptance["events"]:
        if not event.get("sha256") or files.get(event["file"]) != event["sha256"]:
            raise ValueError("unverified HTTP event")
        reply = read_json(root / event["file"])
        # The 202 binds the key (and this request) to the run; a run polled by ID has no POST.
        if event["method"] == "POST" and event["path"] == "/runs":
            bound = event["http_status"] == 202 and reply.get("run_id") == run
        if event["method"] == "GET" and event["path"] == "/runs/" + run and reply.get("status") == "done":
            completed = (event["http_status"] == 200 and reply.get("kind") == "run"
                         and reply.get("run_id") == run and reply.get("result") == result)
    posted = any(event["method"] == "POST" for event in acceptance["events"])
    if not completed or posted and not bound:
        raise ValueError("missing matching run binding/completed HTTP result")
    if not request_binding(body, result):
        raise ValueError("accepted result was run from another request")
    checks = check_result(body, result)
    return {"integrity": "VERIFIED", "job_id": run,
            "submission_id": acceptance["submission_id"], "trade_count": len(result["trades"]),
            "base_npv_usd": result["npv"], "scenario_risk_available": scenario(body),
            "checks": checks, "financial_validation": False, "warnings": result["warnings"]}
