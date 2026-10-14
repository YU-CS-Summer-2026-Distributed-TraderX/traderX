"""Offline verified custody for direct synthetic portfolios, separate from EOD intake.

Hashes detect accidental corruption, not malicious rewriting by a storage owner.
No account authorization or sequenced TraderX export lineage is implied.
"""
import json
import os
from pathlib import Path

from corpus import digest, encoded, load_request
from profiles import CONTRACT, CONTENT_DIGEST_ALGORITHM
from reference import check_result
from request_identity import content_sha256


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
                  "request_content_sha256": state["request_content_sha256"],
                  "request_content_digest": CONTENT_DIGEST_ALGORITHM,
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
    if acceptance.get("request_content_digest") != CONTENT_DIGEST_ALGORITHM:
        raise ValueError("unknown request content digest algorithm")
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
    expected_content = content_sha256((root / "request.json").read_bytes())
    if acceptance.get("request_content_sha256") != expected_content:
        raise ValueError("accepted request content identity mismatch")
    result = read_json(root / "result.json")
    events = acceptance["events"]
    bound = completed = False
    for event in events:
        if not event.get("sha256") or files.get(event["file"]) != event["sha256"]:
            raise ValueError("unverified HTTP event")
        reply = read_json(root / event["file"])
        if event["method"] == "GET" and event["path"] == "/portfolio/submissions/" + acceptance["submission_id"]:
            bound = (event["http_status"] == 200 and reply.get("job_id") == acceptance["job_id"]
                     and reply.get("execution_contract") == CONTRACT and reply.get("request_content_sha256") == expected_content)
        if event["method"] == "GET" and event["path"] == "/jobs/" + acceptance["job_id"] and reply.get("status") == "done":
            completed = (event["http_status"] == 200 and reply.get("execution_contract") == CONTRACT
                         and reply.get("request_content_sha256") == expected_content
                         and reply.get("kind") == "portfolio" and reply.get("result") == result)
    if not bound or not completed:
        raise ValueError("missing matching submission binding/completed HTTP result")
    checks = check_result(body, result)
    return {"integrity": "VERIFIED", "job_id": acceptance["job_id"],
            "submission_id": acceptance["submission_id"], "trade_count": len(result["trades"]),
            "base_npv_usd": result["base_npv"], "scenario_risk_available": result["scenario_risk_available"],
            "checks": checks, "financial_validation": False, "warnings": result["warnings"]}
