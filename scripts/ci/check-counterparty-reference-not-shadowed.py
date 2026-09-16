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

Checks, each a failure:
  1. a volume mountPath that is, is inside, or is an ancestor of /opt/app/classes/reference-data;
  2. RISK_EXTRACT_REFERENCE_DATA set anywhere (it redirects the reader off the image copy);
  3. a counterparties.csv data key (the tracked-ConfigMap form of the same second source);
  4. with --rendered/--state: the rendered CSV differs from the lineage's authoritative spec copy.

Inputs are Kubernetes manifests as YAML or JSON, files or directories, or '-' for stdin, so the
same check reads source manifests, rendered manifests, and a live object:

  kubectl -n traderx get deploy risk-extract -o json | python3 scripts/ci/check-counterparty-reference-not-shadowed.py -

A text scan rather than a YAML parse: the runners have no PyYAML, and a key match reads kustomize
patches, lists and JSON alike.
ponytail: a key split from its value across lines (flow style, anchors) would evade the scan.
"""
import argparse
import pathlib
import re
import sys

REF_DIR = "/opt/app/classes/reference-data"
MOUNT = re.compile(r"""["']?mountPath["']?\s*:\s*["']?([^"'\s,}#]+)""")
ENV = re.compile(r"RISK_EXTRACT_REFERENCE_DATA")
CM_KEY = re.compile(r"""(^\s*|[{,]\s*)["']?counterparties\.csv["']?\s*:""", re.M)
MANIFEST_SUFFIXES = {".yaml", ".yml", ".json"}


def shadows(path):
    path = path.rstrip("/") or "/"
    return path == REF_DIR or path.startswith(REF_DIR + "/") or REF_DIR.startswith(path.rstrip("/") + "/")


def scan_text(name, text):
    problems = []
    for lineno, line in enumerate(text.splitlines(), 1):
        for m in MOUNT.finditer(line):
            if shadows(m.group(1)):
                problems.append(f"{name}:{lineno}: mountPath {m.group(1)} shadows the image's {REF_DIR}")
        if ENV.search(line):
            problems.append(f"{name}:{lineno}: RISK_EXTRACT_REFERENCE_DATA redirects the extract off the image copy")
    for m in CM_KEY.finditer(text):
        lineno = text.count("\n", 0, m.start()) + 1
        problems.append(f"{name}:{lineno}: a counterparties.csv data key is a second source of counterparty truth")
    return problems


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


def self_test():
    incident = """
        volumeMounts:
          - name: counterparties
            mountPath: /opt/app/classes/reference-data/counterparties.csv
            subPath: counterparties.csv
    """
    assert scan_text("incident", incident), "the 2026-08-21 mount must fail"
    assert scan_text("dir", "mountPath: /opt/app/classes/reference-data"), "a directory mount must fail"
    assert scan_text("ancestor", "  - mountPath: '/opt/app/classes'"), "an ancestor mount must fail"
    assert scan_text("json", '{"mountPath":"/opt/app/classes/reference-data/counterparties.csv","name":"c"}')
    assert scan_text("env", "- name: RISK_EXTRACT_REFERENCE_DATA\n  value: /etc/refdata")
    assert scan_text("cm", "kind: ConfigMap\ndata:\n  counterparties.csv: |\n    accountId,x\n")
    assert scan_text("cm-json", '{"data":{"counterparties.csv":"accountId,x"}}')
    benign = """
        volumeMounts:
          - name: extracts
            mountPath: /data/risk-extracts
          - name: shm
            mountPath: /dev/shm
          - name: sibling
            mountPath: /opt/app/classes-extra
        command: ["cat", "/opt/app/classes/reference-data/counterparties.csv"]
    """
    assert not scan_text("benign", benign), scan_text("benign", benign)
    print("[ok] self-test: 7 shadowing forms fail; unrelated mounts and a reader of the file pass")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("manifests", nargs="*", help="manifest files/dirs, or - for stdin")
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

    repo = pathlib.Path(__file__).resolve().parents[2]
    problems, scanned = [], 0
    for arg in args.manifests:
        if arg == "-":
            text = sys.stdin.read()
            if not text.strip():
                problems.append("<stdin> is empty; nothing was checked")  # a failed kubectl must not pass
            problems += scan_text("<stdin>", text)
            scanned += 1
            continue
        for f in manifest_files(arg):
            problems += scan_text(str(f), f.read_text(errors="replace"))
            scanned += 1
    if args.manifests and scanned == 0:
        problems.append(f"no manifests found under {' '.join(args.manifests)}; nothing was checked")
    if args.rendered:
        problems += check_rendered(repo, args.rendered, args.state)

    for p in problems:
        print(f"[fail] {p}", file=sys.stderr)
    if problems:
        return 1
    what = f"{scanned} manifest(s)" + (f" and the rendered CSV for {args.state}" if args.rendered else "")
    print(f"[ok] counterparty reference not shadowed: {what}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
