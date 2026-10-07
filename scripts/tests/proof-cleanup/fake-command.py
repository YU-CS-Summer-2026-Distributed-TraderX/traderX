#!/usr/bin/env python3
"""Offline kubectl/process double. Only writes the test's private JSON rig."""
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import sys
import time

root = Path(os.environ["FAKE_RIG"])
tool = Path(sys.argv[0]).name
args = sys.argv[1:]
if tool == "sleep":
    sys.exit(0)
if tool == "curl":
    print('{"seeded":true}' if "POST" in args else "200")
    sys.exit(0)
if tool == "docker":
    if args[:1] == ["exec"]:
        assert args[2:] == ["crictl", "images", "-o", "json"], args
        print(json.dumps({"images": [{"id": "sha256:" + hashlib.sha256(image.encode()).hexdigest(),
                         "repoTags": [] if "@" in image else [image],
                         "repoDigests": [image] if "@" in image else []}
                        for image in {os.environ["CLUSTER_IMAGE"], os.environ["IMAGE_PRE"], os.environ["IMAGE_FIX"]}]}))
        sys.exit(0)
    print("2026-01-01T00:00:00Z")
    sys.exit(0)
if tool in ("kind", "pkill"):
    sys.exit(0)
while args and args[0].startswith("-"):
    flag = args.pop(0)
    if flag in ("--context", "-n"):
        args.pop(0)
with (root / "lock").open("a") as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    state = json.loads((root / "state.json").read_text())
    with (root / "calls.jsonl").open("a") as f:
        f.write(json.dumps(args) + "\n")
    command = args[0]
    resource = args[1] if len(args) > 1 else ""
    if command in ("get", "scale", "patch") and resource in ("deploy", "sts", "statefulset", "deployment") and len(args) > 2 and not args[2].startswith("-"):
        resource += "/" + args[2]
    resource = resource.replace("statefulset/", "sts/").replace("deployment/", "deploy/")
    out = ""
    failure = False
    hold = False
    if command == "config":
        out = json.dumps({"clusters": [{"cluster": {"server": state["server"]}}]})
    elif command == "get":
        if resource == "nodes":
            obj = {"items": [{"metadata": {"name": "offline-worker"}}]}
        elif resource.startswith("namespace/"):
            obj = {"metadata": {"uid": state["namespaceUID"]}}
        elif resource == "pod" and len(args)>2 and args[2] == "order-matcher-cluster-0":
            m=state["objects"]["sts/order-matcher-cluster"]
            obj={"kind":"Pod", "metadata":{"name":"order-matcher-cluster-0", "namespace":"traderx", "uid":"offline-pod-0",
                 "ownerReferences":[{"kind":"StatefulSet","name":"order-matcher-cluster","controller":True,"uid":m["metadata"]["uid"]}]},
                 "spec":{"containers":copy.deepcopy(m["spec"]["template"]["spec"]["containers"]),
                 "volumes":[{"name":"data","persistentVolumeClaim":{"claimName":"data-order-matcher-cluster-0"}}]}}
        elif resource == "pvc" and len(args)>2 and args[2] == "data-order-matcher-cluster-0":
            obj={"kind":"PersistentVolumeClaim","metadata":{"name":"data-order-matcher-cluster-0","namespace":"traderx", "uid":"offline-pvc-0", "creationTimestamp":"2025-04-01T00:00:00Z"},
                 "status":{"phase":"Bound"},"spec":{"volumeName":"offline-pv-0"}}
        elif resource == "pv" and len(args)>2 and args[2] == "offline-pv-0":
            obj={"kind":"PersistentVolume","metadata":{"name":"offline-pv-0","uid":"offline-pv-uid-0"},"status":{"phase":"Bound"},
                 "spec":{"claimRef":{"name":"data-order-matcher-cluster-0","namespace":"traderx","uid":"offline-pvc-0"}}}
        elif resource in ("pod", "pods"):
            m = state["objects"]["sts/order-matcher-cluster"]
            obj = {"items": [{"metadata": {"name": f"order-matcher-cluster-{i}",
                      "ownerReferences": [{"uid": m["metadata"]["uid"]}]},
                    "spec": {"containers": copy.deepcopy(m["spec"]["template"]["spec"]["containers"])},
                    "status": {"containerStatuses": [{"name": m["spec"]["template"]["spec"]["containers"][0]["name"], "ready": not state.get("unready", False)}]}}
                   for i in range(m["spec"].get("replicas", 1))]}
        elif resource == "pvc":
            obj = {"items": [{"metadata": {"name": f"data-order-matcher-cluster-{i}", "uid": f"offline-pvc-{i}"}} for i in range(3)]}
            journal_file = os.environ.get("PROOF_CLEANUP_JOURNAL")
            j = json.loads(Path(journal_file).read_text()) if journal_file and Path(journal_file).exists() else {}
            mode = os.environ.get("FAKE_COMPATIBILITY", "missing")
            review_path = os.environ.get("PROOF_RETAINED_COMPATIBILITY")
            # Synthetic, independently controlled acceptance artifact. This fake rig cannot
            # establish real snapshot compatibility; no production proof producer is invoked.
            if j.get("phase") == "cleanup" and review_path and mode != "missing":
                request = {"schema": "traderx-retained-restore-request-v1", "journalId": j["journalId"],
                           "target": j["target"], "memberUID": "uid-0",
                           "pvcBoundary": [{"name": f"data-order-matcher-cluster-{i}", "uid": f"offline-pvc-{i}"} for i in range(3)],
                           "writerImages": sorted({os.environ["CLUSTER_IMAGE"], os.environ["IMAGE_PRE"], os.environ["IMAGE_FIX"]}),
                           "restoreImages": {"cluster-node": os.environ["CLUSTER_IMAGE"]}}
                cases = [{"writerImage": writer, "readerImage": os.environ["CLUSTER_IMAGE"],
                          "result": "compatible", "assertions": 3,
                          "checks": {"snapshotRestore": True, "logTailReplay": True, "stateEquality": True}}
                         for writer in request["writerImages"]]
                if mode == "incompatible":
                    cases[0]["result"] = "incompatible"
                elif mode == "readiness-only":
                    for case in cases:
                        case["checks"] = {"ready": True}
                elif mode == "incomplete-writers":
                    cases.pop()
                elif mode == "zero-assertions":
                    cases[0]["assertions"] = 0
                elif mode == "boundary-mismatch":
                    request["pvcBoundary"][0]["uid"] = "older-pvc"
                elif mode == "identity-mismatch":
                    request["restoreImages"]["cluster-node"] = os.environ["IMAGE_PRE"]
                elif mode == "malformed-checks":
                    cases[0]["checks"] = None
                data = json.dumps({"schema": "traderx-retained-restore-evidence-v1", "request": request, "cases": cases}).encode()
                evidence = root / "offline-compatibility-evidence.json"
                evidence.write_bytes(data)
                manifest = {"schema": "traderx-retained-restore-review-v1", "request": request,
                            "verdict": "compatible", "reviewedBy": "offline-fixture-reviewer",
                            "evidencePath": str(evidence.resolve()), "evidenceSha256": hashlib.sha256(data).hexdigest()}
                if mode == "hash-mismatch":
                    manifest["evidenceSha256"] = "0" * 64
                elif mode == "malformed-review":
                    manifest = []
                Path(review_path).write_text(json.dumps(manifest))
        elif resource.startswith("pvc/"):
            obj = None
        elif resource == "deploy":
            obj = {"items": list(state["objects"].values())}
        else:
            obj = state["objects"].get(resource)
        if obj is None:
            if "--ignore-not-found" not in args and not resource.startswith("pvc/"):
                failure = True
        elif "json" in args:
            out = json.dumps(obj)
        elif "name" in args:
            out = resource
        else:
            query = next((a for a in args if "jsonpath=" in a), "")
            c = obj.get("spec", {}).get("template", {}).get("spec", {}).get("containers", [{}])[0]
            if "containers[0].image" in query and "range" not in query:
                out = c.get("image", "")
            elif "containers[0].name" in query:
                out = c.get("name", "")
            elif "containers[0]}" in query:
                out = json.dumps(c)
            elif "spec.replicas" in query and "range" not in query:
                out = str(obj["spec"].get("replicas", 1))
            elif "range .items[*]" in query:
                out = "\n".join(p["spec"]["containers"][0]["image"] + " true" for p in obj["items"])
            elif "startTime" in query:
                out = "2026-02-01T00:00:00Z"
            elif not query:
                out = resource
    elif command == "exec":
        out = "0" if "mariadb" in args else "traderx_cluster_applied 0\ntraderx_book_order_hash 0\ntraderx_cluster_trades 0"
    elif command in ("scale", "patch", "set"):
        if command == "set":
            resource = args[2].replace("deployment/", "deploy/").replace("statefulset/", "sts/")
        obj = state["objects"].get(resource)
        if obj is None:
            failure = True
        elif command == "scale":
            obj["spec"]["replicas"] = int(next(a.split("=", 1)[1] for a in args if a.startswith("--replicas=")))
        elif command == "set":
            name, value = args[3].split("=", 1)
            c = obj["spec"]["template"]["spec"]["containers"][0]
            if args[1] == "image":
                c["image"] = value
            else:
                c.setdefault("env", [])[:] = [e for e in c.get("env", []) if e["name"] != name] + [{"name": name, "value": value}]
        else:
            patch = json.loads(args[args.index("-p") + 1])
            if isinstance(patch, list):
                for op in patch:
                    parts = [p.replace("~1", "/").replace("~0", "~") for p in op["path"].split("/")[1:]]
                    parent = obj
                    for part in parts[:-1]:
                        parent = parent[int(part)] if isinstance(parent, list) else parent[part]
                    key = int(parts[-1]) if isinstance(parent, list) else parts[-1]
                    if op["op"] == "test":
                        assert parent[key] == op["value"]
                    elif op["op"] == "remove":
                        del parent[key]
                    else:
                        parent[key] = op["value"]
            else:
                for c in patch["spec"]["template"]["spec"]["containers"]:
                    old = next(o for o in obj["spec"]["template"]["spec"]["containers"] if o["name"] == c["name"])
                    for key, value in c.items():
                        if value is None:
                            old.pop(key, None)
                        else:
                            old[key] = value
        # Fail AFTER applying part of a preparation, like an ambiguous/partial call.
        scenario = os.environ.get("FAKE_SCENARIO", "success")
        if scenario == "prep-failure" and command == "set" and args[1] == "env":
            failure = True
        if scenario == "cleanup-failure" and command == "patch" and resource == "deploy/grafana":
            # Remove the restoration again so the test observes actual unresolved state.
            obj["spec"]["replicas"] = 0
            failure = True
        journal_path = os.environ.get("PROOF_CLEANUP_JOURNAL", "")
        phase = json.loads(Path(journal_path).read_text()).get("phase") if journal_path and Path(journal_path).exists() else ""
        if scenario == "hold-cleanup" and phase == "cleanup" and command == "patch":
            hold = True
        (root / "state.json").write_text(json.dumps(state))
    elif command == "create" and resource == "configmap":
        literal=next(a for a in args if a.startswith("--from-literal=epochStartMs="))
        out=json.dumps({"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"replay-epoch"},"data":{"epochStartMs":literal.split("=",2)[2]}})
    elif command == "apply":
        manifest=json.load(sys.stdin)
        assert manifest["metadata"]["name"] == "replay-epoch"
        assert manifest["data"]["epochStartMs"] == "1743465600000"
    elif command in ("rollout", "wait", "delete"):
        pass
    elif command == "port-forward":
        hold = True
    else:
        raise SystemExit(f"unhandled fake command {args}")
    if out:
        print(out)
    if failure:
        print("injected command failure", file=sys.stderr)
        sys.exit(1)
if hold:
    if command != "port-forward":
        (root / "cleanup-held").write_text(str(os.getpid()))
    while True:
        time.sleep(.05)
