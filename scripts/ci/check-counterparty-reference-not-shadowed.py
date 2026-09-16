#!/usr/bin/env python3
"""Fail if anything can make the risk extract read counterparties other than the image's copy.

The authoritative counterparty reference is specs/<state>/reference-data/counterparties.csv
(YU15 for YU15 and later; YU14 before it). The state render copies it into order-matcher
resources, the cluster-node image copies those classes to /opt/app/classes, and RiskExtractMain
reads /opt/app/classes/reference-data/counterparties.csv. That is the only delivery path.

On 2026-08-21 a hand-applied ConfigMap was mounted over that single file on the GKE rig. The image
moved to 11 accounts, the mount kept serving 8, and every EOD extract failed closed on account
900001 (issues/risk-extract-counterparties-configmap-shadows-the-image.md). Rolling the image did
nothing, because the mount wins.

Every input is PARSED (YAML 1.1 via PyYAML, which also reads JSON), then walked. Anchors, merge
keys, block scalars, flow style and JSON escapes are resolved by the parser, not guessed at. Input
that is empty, malformed, uses an unsupported tag, or holds no object is a failure, never a pass.

Failures, anywhere in the parsed objects:
  1. a mountPath (or a JSON6902 op on a .../mountPath) that is, is inside, or is an ancestor of
     /opt/app/classes/reference-data, after path normalisation;
  2. RISK_EXTRACT_REFERENCE_DATA as a key, an env name, or inside any string value;
  3. a counterparties.csv data key, or a kustomize generator file/literal named counterparties.csv;
  4. with --rendered/--state: the rendered CSV differs from the lineage's authoritative spec copy.

Two claims, kept apart:
  source mode (default)        no TRACKED manifest shadows the reference. Says nothing about
                               objects applied by hand.
  --live-deployment NAME       the SUPPLIED object stream contains Deployment NAME with a pod
                               template and containers, and it does not shadow the reference:
    kubectl -n traderx get deploy risk-extract -o json \\
      | python3 scripts/ci/check-counterparty-reference-not-shadowed.py --live-deployment risk-extract -
                               This covers the Deployment only. A mount added by another
                               controller or a mutating webhook appears on the Pods, not here.

Requires PyYAML (pip install pyyaml==6.0.2); without it the check exits 2 rather than passing.
"""
import argparse
import pathlib
import posixpath
import re
import sys

try:
    import yaml
except ImportError:
    print("[fail] PyYAML is required (pip install pyyaml==6.0.2); nothing was checked", file=sys.stderr)
    sys.exit(2)

REF_DIR = "/opt/app/classes/reference-data"
ENV_NAME = "RISK_EXTRACT_REFERENCE_DATA"
CSV_NAME = "counterparties.csv"
MANIFEST_SUFFIXES = {".yaml", ".yml", ".json"}
GENERATOR_KEYS = {"files", "literals", "envs"}


def shadows(raw):
    path = posixpath.normpath(raw.strip())
    if not path.startswith("/"):
        return False
    if path.startswith("//"):  # normpath keeps a leading '//'; for a mount it is still '/'
        path = "/" + path.lstrip("/")
    return path == REF_DIR or path.startswith(REF_DIR + "/") or path == "/" or REF_DIR.startswith(path + "/")


def walk(node, where, problems, stats):
    if isinstance(node, dict):
        if node.get("name") == ENV_NAME:
            problems.append(f"{where}: env {ENV_NAME} redirects the extract off the image copy")
        op_path = node.get("path")
        if "op" in node and isinstance(op_path, str) and op_path.rstrip("/").endswith("/mountPath"):
            stats["mounts"] += 1
            if not isinstance(node.get("value"), str) or shadows(node["value"]):
                problems.append(f"{where}: patch {node.get('op')} {op_path} -> {node.get('value')!r} shadows {REF_DIR}")
        for key, value in node.items():
            here = f"{where}.{key}"
            if key == "mountPath":
                stats["mounts"] += 1
                if not isinstance(value, str):
                    problems.append(f"{here}: non-string mountPath {value!r} cannot be checked")
                elif shadows(value):
                    problems.append(f"{here}: mountPath {value!r} shadows the image's {REF_DIR}")
            if key == ENV_NAME:
                problems.append(f"{here}: key {ENV_NAME} redirects the extract off the image copy")
            if key == CSV_NAME:
                problems.append(f"{here}: a {CSV_NAME} data key is a second source of counterparty truth")
            if key in GENERATOR_KEYS and isinstance(value, list):
                for item in value:
                    if isinstance(item, str) and posixpath.basename(item.split("=")[-1].strip()) == CSV_NAME:
                        problems.append(f"{here}: generator source {item!r} is a second source of counterparty truth")
            walk(value, here, problems, stats)
    elif isinstance(node, list):
        for i, item in enumerate(node):
            walk(item, f"{where}[{i}]", problems, stats)
    elif isinstance(node, str) and ENV_NAME in node:
        problems.append(f"{where}: {ENV_NAME} in a value redirects the extract off the image copy")


def objects(docs):
    for doc in docs:
        if isinstance(doc, dict) and doc.get("kind") == "List" and isinstance(doc.get("items"), list):
            yield from (item for item in doc["items"] if isinstance(item, dict))
        elif isinstance(doc, dict):
            yield doc


def live_deployment_problems(name, docs):
    for obj in objects(docs):
        meta = obj.get("metadata") or {}
        if obj.get("kind") == "Deployment" and isinstance(meta, dict) and meta.get("name") == name:
            pod = (((obj.get("spec") or {}).get("template") or {}).get("spec") or {})
            containers = pod.get("containers") if isinstance(pod, dict) else None
            if isinstance(containers, list) and containers:
                return []
            return [f"Deployment {name} has no pod template containers; nothing about it was established"]
    return [f"no Deployment named {name} in the input; nothing about a live deployment was established"]


def check_text(name, text, live_deployment=None):
    """Return (problems, stats). Unparseable or object-free input is a problem, never a pass."""
    stats = {"docs": 0, "mounts": 0}
    if not text.strip():
        return [f"{name}: empty input; nothing was checked"], stats
    try:
        docs = [d for d in yaml.safe_load_all(text) if d is not None]
    except yaml.YAMLError as e:
        return [f"{name}: not parseable as YAML/JSON ({type(e).__name__}); nothing was checked"], stats
    if not docs:
        return [f"{name}: no documents; nothing was checked"], stats
    problems = []
    for i, doc in enumerate(docs):
        if not isinstance(doc, (dict, list)) or not doc:
            problems.append(f"{name}: document {i} is {doc!r}, not a manifest object; nothing was checked")
            continue
        stats["docs"] += 1
        walk(doc, f"{name}#{i}", problems, stats)
    if live_deployment:
        problems += live_deployment_problems(live_deployment, docs)
    return problems, stats


def manifest_files(arg):
    p = pathlib.Path(arg)
    if p.is_dir():
        return sorted(f for f in p.rglob("*") if f.suffix in MANIFEST_SUFFIXES and f.is_file())
    if p.is_file():
        return [p]
    raise SystemExit(f"[fail] no such manifest path: {arg}")  # a missing input must not read as clean


def state_number(state):
    m = re.match(r"YU(\d+)-", state)
    if not m:
        raise SystemExit(f"[fail] --state must be a YUxx state id, got {state!r}")
    return int(m.group(1))


def authoritative_source(repo, state):
    n = state_number(state)
    candidates = sorted(
        (state_number(p.parent.parent.name), p)
        for p in (repo / "specs").glob("YU*-*/reference-data/counterparties.csv")
        if state_number(p.parent.parent.name) <= n
    )
    if not candidates:
        raise SystemExit(f"[fail] no spec counterparties.csv at or before {state}")
    return candidates[-1][1]


def check_rendered(repo, rendered, state):
    source = authoritative_source(repo, state)
    if not rendered.is_file():
        return [f"{rendered}: rendered counterparties.csv is missing (source {source})"]
    if rendered.read_bytes() != source.read_bytes():
        return [f"{rendered}: differs from the authoritative {source.relative_to(repo)}"]
    return []


DEPLOYMENT = """\
apiVersion: apps/v1
kind: Deployment
metadata: {name: risk-extract}
spec:
  template:
    spec:
      containers:
        - name: risk-extract
          command: ["cat", "/opt/app/classes/reference-data/counterparties.csv"]
          volumeMounts:
            - {name: extracts, mountPath: /data/risk-extracts}
            - {name: shm, mountPath: /dev/shm}
            - {name: sibling, mountPath: /opt/app/classes-extra}
"""


def self_test():
    must_fail = {
        "incident (block)": "volumeMounts:\n  - name: c\n    mountPath: /opt/app/classes/reference-data/counterparties.csv\n    subPath: counterparties.csv\n",
        "review: multiline plain scalar": "volumeMounts:\n- name: c\n  mountPath:\n    /opt/app/classes/reference-data/counterparties.csv\n",
        "review: JSON escaped slashes": '{"volumeMounts": [{"name": "c", "mountPath": "\\/opt\\/app\\/classes\\/reference-data\\/counterparties.csv"}]}',
        "block scalar |": "mountPath: |\n  /opt/app/classes/reference-data\n",
        "folded scalar >-": "mountPath: >-\n  /opt/app/classes/reference-data/counterparties.csv\n",
        "anchor + alias": "x: &p /opt/app/classes/reference-data\nvolumeMounts:\n  - {name: c, mountPath: *p}\n",
        "merge key": "base: &b {mountPath: /opt/app/classes}\nvolumeMounts:\n  - <<: *b\n    name: c\n",
        "flow mapping": "{volumeMounts: [{name: c, mountPath: '/opt/app/classes/reference-data/'}]}",
        "unnormalised path": "mountPath: /opt/app/./classes//reference-data/../reference-data\n",
        "ancestor /opt/app": "mountPath: /opt/app\n",
        "root": "mountPath: /\n",
        "JSON6902 replace": '[{"op": "replace", "path": "/spec/template/spec/containers/0/volumeMounts/0/mountPath", "value": "/opt/app/classes/reference-data"}]',
        "env name": "env:\n  - name: RISK_EXTRACT_REFERENCE_DATA\n    value: /etc/refdata\n",
        "configmap data key": "kind: ConfigMap\ndata:\n  counterparties.csv: |\n    accountId,x\n",
        "configMapGenerator files": "configMapGenerator:\n  - name: c\n    files: [refdata/counterparties.csv]\n",
        "configMapGenerator literal": "configMapGenerator:\n  - name: c\n    literals: ['RISK_EXTRACT_REFERENCE_DATA=/etc/refdata']\n",
        "non-string mountPath": "mountPath: [/opt/app/classes]\n",
    }
    must_refuse = {
        "empty": "",
        "whitespace": "  \n",
        "null document": "---\n~\n",
        "malformed": "volumeMounts: [\n",
        "unsupported tag": "mountPath: !Ref RefDir\n",
        "scalar document": "just a string\n",
        "review: empty object": "{}",
        "empty list": "[]",
    }
    for label, text in {**must_fail, **must_refuse}.items():
        problems, _ = check_text(label, text)
        assert problems, f"{label}: expected a failure, got a pass"
    problems, stats = check_text("benign deployment", DEPLOYMENT + "---\n" + DEPLOYMENT)
    assert not problems and stats["mounts"] == 6, (problems, stats)
    live = {
        "review: empty object": ("{}", True),
        "other deployment": (DEPLOYMENT.replace("risk-extract}", "gateway}"), True),
        "no containers": ("kind: Deployment\nmetadata: {name: risk-extract}\nspec: {template: {spec: {}}}\n", True),
        "risk-extract deployment": (DEPLOYMENT, False),
        "kubectl List": ('{"kind": "List", "items": [' + __import__("json").dumps(yaml.safe_load(DEPLOYMENT)) + "]}", False),
    }
    for label, (text, fails) in live.items():
        problems, _ = check_text(label, text, live_deployment="risk-extract")
        assert bool(problems) == fails, f"live {label}: expected {'fail' if fails else 'pass'}, got {problems}"
    print(f"[ok] self-test: {len(must_fail)} shadowing forms fail, {len(must_refuse)} unusable inputs refuse, "
          f"benign controls pass, live mode {sum(f for _, f in live.values())} refuse / "
          f"{sum(not f for _, f in live.values())} pass")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("manifests", nargs="*", help="manifest files/dirs, or - for stdin")
    ap.add_argument("--live-deployment", metavar="NAME",
                    help="the input is a live object stream that must contain Deployment NAME")
    ap.add_argument("--rendered", type=pathlib.Path, help="rendered order-matcher counterparties.csv")
    ap.add_argument("--state", help="state id the rendered tree was generated for, e.g. YU18-eod-risk-bundles")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        self_test()
        return 0
    if not args.manifests and not args.rendered:
        ap.error("nothing to check")
    if bool(args.rendered) != bool(args.state):
        ap.error("--rendered and --state go together")
    if args.live_deployment and args.manifests != ["-"]:
        ap.error("--live-deployment reads exactly one object stream from stdin: pass -")
    if "-" in args.manifests and not args.live_deployment:
        ap.error("stdin is a live object stream: say which Deployment it must contain with --live-deployment")

    repo = pathlib.Path(__file__).resolve().parents[2]
    problems, docs, mounts = [], 0, 0
    inputs = []
    for arg in args.manifests:
        if arg == "-":
            inputs.append(("<stdin>", sys.stdin.read()))
        else:
            inputs += [(str(f), f.read_text(errors="replace")) for f in manifest_files(arg)]
    if args.manifests and not inputs:
        problems.append(f"no manifests found under {' '.join(args.manifests)}; nothing was checked")
    for name, text in inputs:
        found, stats = check_text(name, text, args.live_deployment)
        problems += found
        docs += stats["docs"]
        mounts += stats["mounts"]
    if args.rendered:
        problems += check_rendered(repo, args.rendered, args.state)

    for p in problems:
        print(f"[fail] {p}", file=sys.stderr)
    if problems:
        return 1
    if args.live_deployment:
        print(f"[ok] supplied stream contains Deployment {args.live_deployment}; {docs} document(s), "
              f"{mounts} mountPath(s), none shadow {REF_DIR}")
    elif inputs:
        print(f"[ok] tracked manifests: {len(inputs)} file(s), {docs} document(s), {mounts} mountPath(s); "
              f"none shadow {REF_DIR} (objects applied by hand are not visible here)")
    if args.rendered:
        print(f"[ok] rendered counterparties.csv matches the authoritative spec copy for {args.state}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
