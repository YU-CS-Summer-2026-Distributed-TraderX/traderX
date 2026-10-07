"""Loopback-only single-POST driver with raw request/reply custody and bounded polling."""
import http.client
import json
import math
import re
import shutil
import time
from pathlib import Path
from urllib.parse import quote, urlsplit

from corpus import digest, encoded, load_request
from reference import check_result

MAX_REPLY = 16 * 1024 * 1024
MAX_EVIDENCE = 128 * 1024 * 1024


class Driver:
    def __init__(self, url, evidence, timeout=10, deadline=120, interval=0.5, max_polls=1000):
        parsed = urlsplit(url)
        if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1") or parsed.path not in ("", "/") or parsed.query or parsed.fragment or parsed.username or parsed.password:
            raise ValueError("use a plain loopback HTTP origin")
        if not all(math.isfinite(x) and x > 0 for x in (timeout, deadline, interval)) or not 1 <= max_polls <= 1000:
            raise ValueError("positive finite timing and 1..1000 polls required")
        self.host, self.port = parsed.hostname, parsed.port or 80
        self.evidence = Path(evidence).resolve()
        if any((p / ".git").exists() for p in [self.evidence, *self.evidence.parents]):
            raise ValueError("evidence must be outside Git checkouts")
        self.evidence.mkdir(parents=True, exist_ok=False)
        self.timeout, self.interval, self.max_polls = timeout, interval, max_polls
        self.start, self.end = time.monotonic(), time.monotonic() + deadline
        self.seq, self.bytes = 0, 0
        self.state = {"version": 1, "synthetic": True, "outcome": "NOT_SUBMITTED", "post_attempts": 0,
                      "origin": url, "events": [], "financial_validation": False}
        self.save()

    def save(self):
        self.state["elapsed_seconds"] = time.monotonic() - self.start
        p = self.evidence / "receipt.json"
        tmp = p.with_suffix(".tmp")
        tmp.write_bytes(encoded(self.state) + b"\n")
        tmp.replace(p)

    def finish(self, outcome, detail=None):
        self.state["outcome"] = outcome
        if detail is not None:
            self.state["detail"] = detail
        self.save()
        return self.state

    def exchange(self, method, path, request=None, headers=None):
        remaining = self.end - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("total deadline reached")
        conn = http.client.HTTPConnection(self.host, self.port, timeout=min(self.timeout, remaining))
        prefix = f"reply-{self.seq:04d}"
        self.seq += 1
        event = {"method": method, "path": path, "file": prefix + ".bin"}
        self.state["events"].append(event)
        self.save()
        begin = time.monotonic()
        try:
            conn.putrequest(method, path)
            for k, v in (headers or {}).items():
                conn.putheader(k, v)
            if request:
                conn.putheader("Content-Type", "application/json")
                conn.putheader("Content-Length", str(request.stat().st_size))
            conn.endheaders()
            if request:
                with request.open("rb") as f:
                    for chunk in iter(lambda: f.read(65536), b""):
                        if time.monotonic() >= self.end:
                            raise TimeoutError("total deadline during upload")
                        conn.sock.settimeout(min(self.timeout, self.end - time.monotonic()))
                        conn.send(chunk)
            response = conn.getresponse()
            event.update(http_status=response.status, headers=response.getheaders())
            length = 0
            with (self.evidence / event["file"]).open("xb") as f:
                while True:
                    if time.monotonic() >= self.end:
                        raise TimeoutError("total deadline during download")
                    if conn.sock:
                        conn.sock.settimeout(min(self.timeout, self.end - time.monotonic()))
                    chunk = response.read1(65536)
                    if not chunk:
                        break
                    length += len(chunk)
                    self.bytes += len(chunk)
                    if length > MAX_REPLY or self.bytes > MAX_EVIDENCE:
                        raise ValueError("reply/evidence byte budget exceeded")
                    f.write(chunk)
            event.update(bytes=length, sha256=digest(self.evidence / event["file"]))
            raw = (self.evidence / event["file"]).read_bytes()
            data = json.loads(raw)
            if not isinstance(data, dict):
                raise ValueError("HTTP reply must be a JSON object")
            return response.status, data
        finally:
            event["seconds"] = time.monotonic() - begin
            conn.close()
            self.save()

    def run(self, request, job_id=None, submission_id=None, recover=False):
        if submission_id is not None and not re.fullmatch(r"[A-Za-z0-9._:-]{1,200}", submission_id):
            raise ValueError("submission identity must be 1..200 safe ASCII characters")
        if recover and (not submission_id or job_id):
            raise ValueError("recover requires submission_id and no job_id")
        load_request(request)
        snapshot = self.evidence / "request.json"
        shutil.copyfile(request, snapshot)
        body = load_request(snapshot)
        self.state.update(request_sha256=digest(snapshot), request_bytes=snapshot.stat().st_size,
                          submission_id=submission_id)
        self.save()
        try:
            if recover:
                code, reply = self.exchange("GET", "/portfolio/submissions/" + quote(submission_id, safe=""))
                if code != 200 or not isinstance(reply.get("job_id"), str):
                    return self.finish("UNKNOWN", f"submission recovery HTTP {code}; no POST")
                job_id = reply["job_id"]
            if job_id is None:
                # Persist uncertainty before any byte is sent. Never retry POST, even on 500.
                self.state.update(outcome="SUBMISSION_UNKNOWN", post_attempts=1)
                self.save()
                code, reply = self.exchange("POST", "/portfolio/price", snapshot,
                                            {"Idempotency-Key": submission_id} if submission_id else {})
                if code != 202:
                    return self.finish("REJECTED" if code in (400, 409, 413, 422, 429) else "SUBMISSION_UNKNOWN",
                                       f"HTTP {code}; no POST retry")
                job_id = reply.get("job_id")
                if not isinstance(job_id, str) or not job_id or len(job_id) > 200:
                    return self.finish("SUBMISSION_UNKNOWN", "accepted response lacks valid job_id")
            if not isinstance(job_id, str) or not job_id or len(job_id) > 200:
                raise ValueError("invalid resume job_id")
            self.state.update(job_id=job_id, outcome="ACCEPTED")
            self.save()
            for _ in range(self.max_polls):
                code, reply = self.exchange("GET", "/portfolio/price/" + quote(job_id, safe=""))
                if code == 404:
                    return self.finish("UNKNOWN", "job not found; no resubmission")
                if code != 200:
                    return self.finish("UNKNOWN", f"poll HTTP {code}; retained job identity")
                status = reply.get("status")
                if status == "done":
                    try:
                        verdict = check_result(body, reply["result"])
                    except (ValueError, KeyError, TypeError) as exc:
                        return self.finish("INVALID_RESULT", str(exc))
                    self.state["result_checks"] = verdict
                    return self.finish("DONE")
                if status in ("failed", "interrupted"):
                    return self.finish(status.upper(), "terminal; no automatic resubmission")
                if status not in ("pending", "running"):
                    return self.finish("UNKNOWN", "unrecognized job state")
                self.state["last_job_status"] = status
                self.save()
                delay = min(self.interval, max(0, self.end - time.monotonic()))
                time.sleep(delay)
            return self.finish("TIMEOUT", "poll limit reached; retained job identity")
        except KeyboardInterrupt:
            return self.finish("CLIENT_INTERRUPTED", "request outcome may be uncertain; no retry")
        except (OSError, ValueError, http.client.HTTPException, TypeError, KeyError) as exc:
            return self.finish("TIMEOUT" if isinstance(exc, TimeoutError) and job_id else
                               "UNKNOWN" if job_id else "SUBMISSION_UNKNOWN", str(exc))
