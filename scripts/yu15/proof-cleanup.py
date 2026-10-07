#!/usr/bin/env python3
"""Supervise the proof runner and restore its borrowed Kubernetes configuration.

The journal deliberately does not restore trades, PVCs or an old replay anchor.
An explicitly authorized epoch reset is irreversible, not a cleanup operation.
"""
import argparse
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import uuid


FIELDS = ("image", "env", "startupProbe", "readinessProbe", "livenessProbe")
RESOURCES = ["sts/order-matcher-cluster", "deploy/cluster-gateway",
             "deploy/feed-adapter", "deploy/risk-extract", "deploy/execution-algo-engine",
             "deploy/price-publisher", "deploy/grafana", "deploy/loki", "deploy/tempo",
             "deploy/prometheus", "deploy/otel-collector", "deploy/trade-processor"]
CLIENTS = ["deploy/cluster-gateway", "deploy/feed-adapter",
           "deploy/execution-algo-engine", "deploy/price-publisher", "deploy/risk-extract",
           "deploy/trade-processor"]
RESTART = "kubectl.kubernetes.io/restartedAt"
interrupted = 0
child = None


def on_signal(sig, _frame):
    global interrupted
    interrupted = sig
    if child is not None and child.poll() is None:
        try:
            os.killpg(child.pid, sig)
        except ProcessLookupError:
            pass


class Refusal(RuntimeError):
    pass


def save(path, value):
    """Commit intent before commands; fsync both bytes and directory entry."""
    tmp = path.with_suffix(".tmp")
    with tmp.open("w") as f:
        os.chmod(tmp, 0o600)
        json.dump(value, f, indent=2, sort_keys=True)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)
    fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


class Rig:
    def __init__(self, context, namespace, lease_fd=None):
        self.context, self.namespace = context, namespace
        self.deadline = None
        self.lease_fd = lease_fd
        self.expected_uids = {}
        self.prefix = ["kubectl", "--context", context, "-n", namespace,
                       "--request-timeout=30s"]

    def command(self, *args):
        budget = 660 if self.deadline is None else self.deadline - time.monotonic()
        if budget <= 0:
            raise Refusal("cleanup command budget exhausted; explicit recovery required")
        try:
            p = subprocess.run(self.prefix + list(args), text=True, capture_output=True,
                               timeout=budget, pass_fds=(() if self.lease_fd is None else (self.lease_fd,)))
        except subprocess.TimeoutExpired as e:
            raise Refusal(f"kubectl timed out: {args}") from e
        if p.returncode:
            raise Refusal(f"kubectl {args}: {p.stderr.strip() or p.stdout.strip()}")
        return p.stdout

    def get(self, resource, optional=False):
        args = ["get", resource, "-o", "json"]
        if optional:
            args += ["--ignore-not-found"]
        data = self.command(*args)
        return json.loads(data) if data.strip() else None

    def identity(self):
        config = json.loads(self.command("config", "view", "--minify", "--raw", "-o", "json"))
        cluster = config["clusters"][0]["cluster"]
        # Never persist tokens, certificates or the whole kubeconfig.
        return {"context": self.context, "namespace": self.namespace,
                "server": cluster["server"],
                "clusterFingerprint": hashlib.sha256(json.dumps(cluster, sort_keys=True).encode()).hexdigest(),
                "namespaceUID": self.get("namespace/" + self.namespace)["metadata"]["uid"]}

    def patch(self, resource, ops):
        if ops:
            self.command("patch", resource, "--type=json", "-p", json.dumps(ops))

    def scale(self, resource, replicas):
        # The UID test and write are one API operation, so a workload replaced after
        # preflight cannot accidentally receive the old journal's replica change.
        self.patch(resource, [{"op": "test", "path": "/metadata/uid", "value": self.expected_uids[resource]},
                              {"op": "add", "path": "/spec/replicas", "value": replicas}])

    def settled(self, resource, replicas):
        if replicas:
            self.command("rollout", "status", resource, "--timeout=600s")


def view(obj):
    spec = obj["spec"]
    template = spec["template"]
    containers = template["spec"]["containers"]
    return {"replicas": {"present": "replicas" in spec, "value": spec.get("replicas")},
            "containers": {c["name"]: {k: copy.deepcopy(c[k]) for k in FIELDS if k in c}
                           for c in containers},
            "restart": {"present": RESTART in template.get("metadata", {}).get("annotations", {}),
                        "value": template.get("metadata", {}).get("annotations", {}).get(RESTART)}}


def restore_ops(obj, wanted):
    ops = [{"op": "test", "path": "/metadata/uid", "value": obj["metadata"]["uid"]}]
    containers = obj["spec"]["template"]["spec"]["containers"]
    if {c["name"] for c in containers} != set(wanted["containers"]):
        raise Refusal("container membership changed; manual reconciliation required")
    for i, c in enumerate(containers):
        for key in FIELDS:
            path = f"/spec/template/spec/containers/{i}/{key}"
            old = wanted["containers"][c["name"]]
            if key in old:
                if key not in c or c[key] != old[key]:
                    ops.append({"op": "add", "path": path, "value": old[key]})
            elif key in c:
                ops.append({"op": "remove", "path": path})
    spec = obj["spec"]
    r = wanted["replicas"]
    if r["present"]:
        if spec.get("replicas") != r["value"]:
            ops.append({"op": "add", "path": "/spec/replicas", "value": r["value"]})
    elif "replicas" in spec:
        ops.append({"op": "remove", "path": "/spec/replicas"})
    current = view(obj)["restart"]
    r = wanted["restart"]
    if current != r:
        path = "/spec/template/metadata/annotations/kubectl.kubernetes.io~1restartedAt"
        if r["present"]:
            if "annotations" not in spec["template"].get("metadata", {}):
                ops.append({"op": "add", "path": "/spec/template/metadata/annotations", "value": {}})
            ops.append({"op": "add", "path": path, "value": r["value"]})
        else:
            ops.append({"op": "remove", "path": path})
    return ops if len(ops) > 1 else []


def check_target(rig, journal):
    if journal["target"] != rig.identity():
        raise Refusal("journal target differs from this rig; refusing all cleanup mutations")
    # Preflight ALL UIDs before any write, even if the first object would be restorable.
    objects = {}
    for resource, record in journal["resources"].items():
        obj = rig.get(resource, optional=True)
        if record is None:
            if obj is not None:
                raise Refusal(f"{resource} was absent and now exists; manual reconciliation required")
        elif obj is None or obj["metadata"]["uid"] != record["uid"]:
            raise Refusal(f"{resource} UID changed or disappeared; refusing cleanup")
        objects[resource] = obj
    return objects


def checked_object(rig, resource, journal):
    obj = rig.get(resource)
    if obj["metadata"]["uid"] != journal["resources"][resource]["uid"]:
        raise Refusal(f"{resource} UID changed during cleanup")
    return obj


def require_pvc(obj):
    spec = obj["spec"]
    if spec.get("persistentVolumeClaimRetentionPolicy", {}).get("whenScaled", "Retain") != "Retain":
        raise Refusal("member PVCs are deleted on scale-down; refusing automatic stop/reset")
    claims = {c["metadata"]["name"] for c in spec.get("volumeClaimTemplates", [])}
    pod = spec["template"]["spec"]
    mounts = [m for c in pod["containers"] for m in c.get("volumeMounts", []) if m["mountPath"] == "/data"]
    if not mounts or any(m["name"] not in claims for m in mounts):
        raise Refusal("member /data lacks PVC backing; manual recovery required, no automatic stop/reset")
    if any(v["name"] in {m["name"] for m in mounts} for v in pod.get("volumes", [])):
        raise Refusal("member data claim is shadowed by a pod volume; refusing automatic recovery")


def retained_compatibility(rig, path, journal, obj, desired):
    """Consume a separately reviewed proof; labels, tags and Ready cannot issue one.

    This component supplies no real compatibility-proof producer. Default is refusal.
    The offline fake CLI supplies synthetic evidence only in process acceptance tests.
    """
    if journal.get("version") != 2 or not journal.get("journalId") or not journal.get("memberWriterImages"):
        raise Refusal("legacy/unknown retained writer history; reviewed compatibility required")
    writer_images = sorted(set(journal["memberWriterImages"]))
    restore_images = {name: c["image"] for name, c in desired["containers"].items()}
    current_images = {c["image"] for c in obj["spec"]["template"]["spec"]["containers"]}
    if not current_images.issubset(set(writer_images)):
        raise Refusal("unrecorded member writer image; reviewed compatibility required")
    pvcs = json.loads(rig.command("get", "pvc", "-o", "json"))["items"]
    pod = obj["spec"]["template"]["spec"]
    data_claims = {m["name"] for c in pod["containers"] for m in c.get("volumeMounts", []) if m["mountPath"] == "/data"}
    prefixes = [name + "-order-matcher-cluster-" for name in data_claims]
    relevant = [p for p in pvcs if any(p["metadata"]["name"].startswith(prefix) and
                p["metadata"]["name"][len(prefix):].isdigit() for prefix in prefixes)]
    boundary = sorted([{"name": p["metadata"]["name"], "uid": p["metadata"]["uid"]} for p in relevant], key=lambda p: p["name"])
    count = max(obj["spec"].get("replicas", 1), desired["replicas"]["value"] if desired["replicas"]["present"] else 1)
    expected_claims = {prefix + str(i) for prefix in prefixes for i in range(count)}
    if not boundary or not expected_claims.issubset({p["name"] for p in boundary}) or any(not p["uid"] for p in boundary):
        raise Refusal("cannot identify complete retained PVC boundary; compatibility refused")
    request = {"schema": "traderx-retained-restore-request-v1", "journalId": journal["journalId"],
               "target": journal["target"], "memberUID": journal["resources"]["sts/order-matcher-cluster"]["uid"],
               "pvcBoundary": boundary, "writerImages": writer_images, "restoreImages": restore_images}
    journal["requiredCompatibility"] = request
    save(path, journal)
    # Mutable references cannot bind a compatibility result to the binary that writes/reads.
    if any(not re.fullmatch(r".+@sha256:[0-9a-f]{64}", image)
           for image in writer_images + list(restore_images.values())):
        raise Refusal("mutable/unknown member image identity; retained compatibility refused")
    proof_path = os.environ.get("PROOF_RETAINED_COMPATIBILITY")
    if not proof_path or not Path(proof_path).is_file():
        raise Refusal("member image restoration requires separately reviewed retained compatibility evidence")
    manifest = json.loads(Path(proof_path).read_text())
    if (not isinstance(manifest, dict) or manifest.get("schema") != "traderx-retained-restore-review-v1" or
            manifest.get("request") != request or manifest.get("verdict") != "compatible" or
            not isinstance(manifest.get("reviewedBy"), str) or not manifest["reviewedBy"].strip()):
        raise Refusal("retained compatibility review does not match this restoration boundary")
    evidence = Path(manifest["evidencePath"])
    if not evidence.is_absolute():
        raise Refusal("compatibility evidence must name an absolute local artifact")
    data = evidence.read_bytes()
    if hashlib.sha256(data).hexdigest() != manifest.get("evidenceSha256"):
        raise Refusal("compatibility evidence hash differs from the reviewed artifact")
    result = json.loads(data)
    if not isinstance(result, dict) or result.get("schema") != "traderx-retained-restore-evidence-v1" or result.get("request") != request:
        raise Refusal("compatibility evidence describes another boundary")
    expected = {(writer, reader) for writer in writer_images for reader in restore_images.values()}
    covered = set()
    cases = result.get("cases", [])
    if not isinstance(cases, list):
        raise Refusal("retained compatibility evidence has malformed cases")
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get("checks"), dict):
            raise Refusal("retained compatibility evidence has malformed checks")
        pair = (case.get("writerImage"), case.get("readerImage"))
        checks = case.get("checks", {})
        if (pair not in expected or pair in covered or case.get("result") != "compatible" or
                type(case.get("assertions")) is not int or case["assertions"] <= 0 or
                any(checks.get(k) is not True for k in ("snapshotRestore", "logTailReplay", "stateEquality"))):
            raise Refusal("retained compatibility evidence has failed, missing or duplicate checks")
        covered.add(pair)
    if covered != expected:
        raise Refusal("retained compatibility evidence does not cover every writer/reader pair")
    journal["compatibilityEvidence"] = {"review": str(Path(proof_path).resolve()),
                                       "sha256": manifest["evidenceSha256"], "reviewedBy": manifest["reviewedBy"]}
    save(path, journal)


def cleanup(rig, path, journal):
    global interrupted
    # One rollout budget plus request overhead for the whole cleanup, rather than
    # eleven independent ten-minute waits. Retries use the same recorded intent.
    rig.deadline = time.monotonic() + 660
    objects = check_target(rig, journal)
    rig.expected_uids = {r: record["uid"] for r, record in journal["resources"].items() if record is not None}
    journal["phase"] = "cleanup"
    journal["pendingResources"] = [r for r, record in journal["resources"].items() if record is not None]
    save(path, journal)
    unresolved = []

    def checkpoint(step):
        if interrupted:
            raise Refusal("cleanup interrupted; explicit recovery required")
        journal["nextStep"] = step
        save(path, journal)

    member = "sts/order-matcher-cluster"
    desired = journal["resources"][member]["state"]
    # Full stop avoids mixed deterministic-core versions. Refuse an emptyDir stop: a
    # configuration restore must not quietly destroy surviving engine state.
    member_changed = view(objects[member]) != desired
    image_changed = {c["name"]: c["image"] for c in objects[member]["spec"]["template"]["spec"]["containers"]} != {
        name: c["image"] for name, c in desired["containers"].items()}
    # A previous cleanup may have patched the image but not restarted the members.
    needs_compatibility = image_changed or journal.get("memberImageRestoreRequired", False)
    if journal.get("version") != 2 and journal.get("memberRestoreRequired"):
        needs_compatibility = True
    blocked = False
    if member_changed or needs_compatibility:
        journal["memberRestoreRequired"] = True
        journal["memberImageRestoreRequired"] = needs_compatibility
        save(path, journal)
        require_pvc(objects[member])
        for resource in CLIENTS:
            if journal["resources"][resource] is not None:
                checkpoint("quiet " + resource)
                rig.scale(resource, 0)
        if needs_compatibility:
            checkpoint("verify retained image compatibility before member stop/patch/start")
            try:
                retained_compatibility(rig, path, journal, objects[member], desired)
            except (Refusal, OSError, ValueError, KeyError, TypeError) as e:
                blocked = True
                unresolved.append(f"{member}: {e}")
        if not blocked:
            checkpoint("stop all members without deleting storage")
            rig.scale(member, 0)
            selector = objects[member]["spec"]["selector"]["matchLabels"]
            rig.command("wait", "--for=delete", "pod", "-l",
                        ",".join(k + "=" + v for k, v in sorted(selector.items())), "--timeout=300s")
            checkpoint("restore members")
            obj = checked_object(rig, member, journal)
            if needs_compatibility:
                retained_compatibility(rig, path, journal, obj, desired)
            # Restore template while stopped, then original replica count in a second command.
            stopped = copy.deepcopy(desired)
            stopped["replicas"] = {"present": True, "value": 0}
            rig.patch(member, restore_ops(obj, stopped))
            checkpoint("restart original member count")
            if needs_compatibility:
                retained_compatibility(rig, path, journal, checked_object(rig, member, journal), desired)
            rig.patch(member, restore_ops(checked_object(rig, member, journal), desired))
            rig.settled(member, desired["replicas"]["value"] if desired["replicas"]["present"] else 1)
    if not blocked and (member_changed or journal.get("memberRestoreRequired")):
        # Retry still verifies the pods even if an earlier cleanup already patched the template.
        # Rollout completion alone is insufficient: inspect the actual member containers.
        pods = rig.get("pods")["items"]
        expected = desired["replicas"]["value"] if desired["replicas"]["present"] else 1
        pods = [p for p in pods if any(o.get("uid") == objects[member]["metadata"]["uid"]
                                      for o in p["metadata"].get("ownerReferences", []))]
        if len(pods) != expected or any(
            {c["name"] for c in p["spec"]["containers"]} != set(desired["containers"]) or
            any(c["image"] != desired["containers"].get(c["name"], {}).get("image")
                for c in p["spec"]["containers"]) or
            {c["name"] for c in p.get("status", {}).get("containerStatuses", [])} != set(desired["containers"]) or
            not all(c.get("ready") for c in p["status"]["containerStatuses"])
            for p in pods):
            raise Refusal("original members have not all returned on their recorded images and ready")
    for resource, record in journal["resources"].items():
        if record is None or (blocked and resource == member):
            continue
        try:
            checkpoint("restore " + resource)
            obj = checked_object(rig, resource, journal)
            wanted = copy.deepcopy(record["state"])
            if blocked and resource in CLIENTS:
                wanted["replicas"] = {"present": True, "value": 0}
            ops = restore_ops(obj, wanted)
            if ops:
                rig.patch(resource, ops)
            r = wanted["replicas"]
            rig.settled(resource, r["value"] if r["present"] else 1)
            if view(rig.get(resource)) != wanted:
                raise Refusal("recorded fields differ after restoration")
            if not (blocked and resource in CLIENTS):
                journal["pendingResources"].remove(resource)
            save(path, journal)
        except Refusal as e:
            unresolved.append(f"{resource}: {e}")
            if interrupted:
                break
    if unresolved:
        journal["unresolved"] = unresolved
        save(path, journal)
        raise Refusal("; ".join(unresolved))
    journal.update(phase="complete", nextStep=None, unresolved=[])
    save(path, journal)
    print("[cleanup] recorded configuration restored; no storage reset performed", flush=True)


def main():
    global child, interrupted
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["run", "recover", "reset-intent", "check-storage", "writer-intent"])
    parser.add_argument("--context", default=os.environ.get("CTX", "kind-traderx-yu12-cluster"))
    parser.add_argument("--namespace", default=os.environ.get("NS", "traderx"))
    parser.add_argument("--journal", default=os.environ.get("PROOF_CLEANUP_JOURNAL"))
    parser.add_argument("--image", action="append", default=[])
    argv = sys.argv[1:]
    command = []
    if "--" in argv:
        split = argv.index("--")
        argv, command = argv[:split], argv[split + 1:]
    args = parser.parse_args(argv)
    root = Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local/state"))) / "traderx/proof-cleanup"
    key = hashlib.sha256((args.context + "\0" + args.namespace).encode()).hexdigest()[:24]
    path = Path(args.journal) if args.journal else root / (key + ".json")
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if args.action == "check-storage":
        j = json.loads(path.read_text())
        rig = Rig(args.context, args.namespace)
        check_target(rig, j)
        require_pvc(checked_object(rig, "sts/order-matcher-cluster", j))
        return 0
    if args.action in ("reset-intent", "writer-intent"):
        if args.action == "reset-intent" and os.environ.get("ALLOW_PROOF_RESET") != "1":
            raise Refusal("epoch reset requires ALLOW_PROOF_RESET=1")
        j = json.loads(path.read_text())
        if j["target"]["context"] != args.context or j["target"]["namespace"] != args.namespace:
            raise Refusal("reset intent target mismatch")
        if args.action == "reset-intent":
            j.setdefault("irreversible", []).append("authorized engine/PVC/projection reset; prior data cannot be restored")
        j["memberWriterImages"] = sorted(set(j.get("memberWriterImages", []) + args.image))
        save(path, j)
        return 0
    with path.with_suffix(".lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as e:
            raise Refusal("another proof runner/recovery owns this journal") from e
        signal.signal(signal.SIGTERM, on_signal)
        signal.signal(signal.SIGINT, on_signal)
        rig = Rig(args.context, args.namespace, lock.fileno())
        j = json.loads(path.read_text()) if path.exists() else None
        if args.action == "recover":
            if j is None:
                raise Refusal("no journal to recover")
            if j["phase"] == "complete":
                check_target(rig, j)
                print("[cleanup] journal already complete; no writes")
                return 0
            try:
                cleanup(rig, path, j)
            except (Refusal, OSError, ValueError) as e:
                j.update(phase="unfinished", cleanupExit=1,
                         unresolved=j.get("unresolved", []) + [str(e)])
                save(path, j)
                raise
            j["cleanupExit"] = 0
            save(path, j)
            return 0
        if j and j["phase"] != "complete":
            raise Refusal(f"unfinished proof cleanup: {path}; use explicit recover on the recorded rig")
        if not command:
            raise Refusal("run requires a command")
        resources = {}
        target = rig.identity()
        for resource in RESOURCES:
            obj = rig.get(resource, optional=resource not in RESOURCES[:2])
            resources[resource] = {"uid": obj["metadata"]["uid"], "state": view(obj)} if obj else None
        if target != rig.identity():
            raise Refusal("target changed while capturing original state")
        j = {"version": 2, "journalId": str(uuid.uuid4()), "phase": "running", "target": target, "resources": resources,
             "memberWriterImages": sorted({c["image"] for c in resources["sts/order-matcher-cluster"]["state"]["containers"].values()}),
             "proofExit": None, "cleanupExit": None, "unresolved": [], "irreversible": [],
             "pendingResources": [r for r, record in resources.items() if record is not None]}
        save(path, j)
        if interrupted:
            raise Refusal("interrupted before proof launch; journal retained")
        print(f"[cleanup] journal {path}", flush=True)
        env = dict(os.environ, PROOF_CLEANUP_CHILD="1", PROOF_CLEANUP_JOURNAL=str(path),
                   CTX=args.context, NS=args.namespace)
        # Inherit the lease in the runner. SIGKILL of this supervisor alone must not let
        # recovery race an orphan runner still changing the rig.
        child = subprocess.Popen(command, env=env, start_new_session=True,
                                 pass_fds=(lock.fileno(),))
        j["runnerPID"] = child.pid
        save(path, j)
        while child.poll() is None:
            if interrupted:
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
            else:
                time.sleep(.05)
        proof_exit = 128 + interrupted if interrupted else (child.returncode if child.returncode >= 0 else 128 - child.returncode)
        # Reap every owned helper/forward before restoration. Never pkill other lanes.
        try:
            os.killpg(child.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        # reset-intent was written by the supervised runner before a destructive operation.
        j = json.loads(path.read_text())
        j["proofExit"] = proof_exit
        save(path, j)
        interrupted = 0
        try:
            cleanup(rig, path, j)
            cleanup_exit = 0
        except (Refusal, OSError, ValueError) as e:
            cleanup_exit = 1
            j.update(phase="unfinished", unresolved=j.get("unresolved", []) + [str(e)])
            save(path, j)
            print(f"[cleanup] FAILED: {e}; journal retained at {path}", file=sys.stderr)
        j["cleanupExit"] = cleanup_exit
        save(path, j)
        print(f"[result] proof_exit={proof_exit} cleanup_exit={cleanup_exit}", flush=True)
        if not proof_exit and not cleanup_exit:
            print("[result] RUN COMPLETE", flush=True)
        else:
            print("[result] RUN FAILED / INTERRUPTED", flush=True)
        return proof_exit or (128 + interrupted if interrupted else (70 if cleanup_exit else 0))


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (Refusal, OSError, ValueError, KeyError, IndexError) as exc:
        print(f"[cleanup] REFUSED: {exc}", file=sys.stderr)
        sys.exit(70)
