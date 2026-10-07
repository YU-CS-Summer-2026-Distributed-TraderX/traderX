#!/usr/bin/env python3
"""Measure current YU18 LimitBook objects in one bounded, disposable local JVM."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
PREFIX = "generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/"
OWNERS = {
    "lmax/LimitBook.java": "YU18-risk-integration",
    "lmax/RestingOrder.java": "YU18-risk-integration",
    "lmax/InputEvent.java": "YU18-risk-integration",
    "lmax/OrderTypes.java": "YU18-risk-integration",
    "lmax/Px.java": "YU02-lmax-kubernetes",
    "risk/ReservationHolder.java": "YU03-in-memory-risk-gateway",
    "lmax/MatchingEngine.java": "YU18-risk-integration",
}


def run(command, timeout=30):
    """No shell, installs, services or background JVMs; timeout kills/reaps child."""
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {command!r}\n{result.stderr}\n{result.stdout}")
    return result


def digest(data):
    return hashlib.sha256(data).hexdigest()


def sources(root):
    """Explicit current source owners; refuse a newly shadowing YU layer."""
    result = {}
    for suffix, owner in OWNERS.items():
        relative = Path("specs") / owner / PREFIX / suffix
        selected = root / relative
        candidates = sorted(root.glob(f"specs/YU*/{PREFIX}{suffix}"))
        rank = int(re.match(r"YU(\d+)", owner)[1])
        for candidate in candidates:
            candidate_rank = int(re.match(r"YU(\d+)", candidate.relative_to(root).parts[1])[1])
            if rank <= candidate_rank <= 18 and candidate != selected:
                raise ValueError(f"source owner must be reviewed: {candidate}")
        data = selected.read_bytes()
        result[suffix] = {"path": relative.as_posix(), "sha256": digest(data), "bytes": data,
                          "shadow_candidates": [p.relative_to(root).as_posix() for p in candidates]}
    return result


def default_levels(engine):
    matches = re.findall(r"public static final int DEFAULT_BOOK_LEVELS\s*=\s*1\s*<<\s*(\d+)\s*;", engine)
    if len(matches) != 1:
        raise ValueError("DEFAULT_BOOK_LEVELS source form changed; review before measuring")
    return 1 << int(matches[0])


def parse_report(stdout):
    prefix = "BOOK_MEMORY_JSON:"
    reports = [line[len(prefix):] for line in stdout.splitlines() if line.startswith(prefix)]
    if len(reports) != 1:
        raise ValueError("expected exactly one executed JVM measurement report")
    return json.loads(reports[0])


def validate(report):
    """Deterministic arithmetic/schema checks; runtime object sizes are not hardcoded."""
    if report["schema"] != 1 or report["units"] != "bytes" or report["measurement"] != "instrumentation-shallow":
        raise ValueError("invalid measurement schema")
    fixture = report["fixture"]
    if len(report["empty_books"]) != fixture["book_count"] or len(report["occupied_books"]) != fixture["book_count"]:
        raise ValueError("fixture did not execute requested books")
    total = 0
    for empty, occupied in zip(report["empty_books"], report["occupied_books"]):
        for book in (empty, occupied):
            arrays = book["arrays"]
            # Independent contract from current constructor, rather than probe enumeration.
            expected = {name: fixture["levels"] for name in
                        ("bidHead", "bidTail", "askHead", "askTail", "bidQty", "askQty")}
            expected.update({"bidBits": fixture["levels"] >> 6, "askBits": fixture["levels"] >> 6})
            if len(arrays) != 8 or {a["field"]: a["length"] for a in arrays} != expected:
                raise ValueError("incomplete or wrong book-array inventory")
            for array in arrays:
                expected_type = "[Lfinos.traderx.ordermatcher.lmax.RestingOrder;" if array["field"].endswith(("Head", "Tail")) else "[J"
                if array["type"] != expected_type or (expected_type == "[J" and array["element_bytes"] != 8):
                    raise ValueError("wrong array element category")
                if array["element_bytes"] <= 0 or array["payload_bytes"] != array["length"] * array["element_bytes"]:
                    raise ValueError("invalid payload arithmetic")
                if array["shallow_bytes"] != array["payload_bytes"] + array["base_offset_bytes"] + array["alignment_padding_bytes"]:
                    raise ValueError("invalid shallow/header/padding arithmetic")
                if array["base_offset_bytes"] <= 0 or array["alignment_padding_bytes"] < 0:
                    raise ValueError("invalid array overhead")
            for aggregate, field in (("array_payload_bytes", "payload_bytes"), ("array_shallow_bytes", "shallow_bytes"),
                                     ("array_base_offset_bytes", "base_offset_bytes"),
                                     ("array_alignment_padding_bytes", "alignment_padding_bytes")):
                if book[aggregate] != sum(a[field] for a in arrays):
                    raise ValueError(f"invalid aggregate {aggregate}")
            if book["book_object_shallow_bytes"] <= 0 or book["book_owned_object_count"] != 9:
                raise ValueError("missing book object category")
            owned = book["book_object_shallow_bytes"] + book["array_shallow_bytes"]
            if owned != book["book_owned_reachable_shallow_bytes"]:
                raise ValueError("invalid owned shallow sum")
            if book["book_and_shared_reachable_union_shallow_bytes"] != owned + book["shared_pool_reachable_order_shallow_bytes"]:
                raise ValueError("invalid reachable union")
            if book["levels"] != fixture["levels"] or book["tick_ticks"] != fixture["tick_ticks"]:
                raise ValueError("wrong fixture configuration")
        if empty["open_orders"] != 0 or empty["shared_pool_reachable_order_count"] != 0 or empty["shared_pool_reachable_order_shallow_bytes"] != 0:
            raise ValueError("empty fixture contains orders")
        if occupied["open_orders"] != fixture["orders_per_book"] or occupied["shared_pool_reachable_order_count"] != fixture["orders_per_book"]:
            raise ValueError("nonempty fixture did not execute/deduplicate requested orders")
        if empty["book_owned_reachable_shallow_bytes"] != occupied["book_owned_reachable_shallow_bytes"]:
            raise ValueError("owned book bytes changed when attaching pooled orders")
        if fixture["orders_per_book"] > 0 and occupied["shared_pool_reachable_order_shallow_bytes"] <= 0:
            raise ValueError("missing shared order category")
        total += empty["book_owned_reachable_shallow_bytes"]
    if total != fixture["total_book_owned_shallow_bytes"]:
        raise ValueError("invalid book-count total")
    if fixture["pool_owned_shallow_bytes"] != fixture["pool_array_shallow_bytes"] + fixture["pool_entries_shallow_bytes"]:
        raise ValueError("invalid fixture pool total")
    if fixture["books_plus_entire_fixture_pool_union_shallow_bytes"] != total + fixture["pool_owned_shallow_bytes"]:
        raise ValueError("fixture pool double-counted")
    for category in ("constructor_allocated_bytes", "retained_heap_bytes", "production_pool_output_engine_bytes"):
        if not report["unavailable"][category]:
            raise ValueError("unmeasured category must be explicit")


def measure(args, root=ROOT):
    selected = sources(root)
    default = default_levels(selected["lmax/MatchingEngine.java"]["bytes"].decode())
    levels = default if args.levels is None else args.levels
    if default > 131072 or levels < 64 or levels > 131072 or levels & (levels - 1):
        raise ValueError("levels must be a power of two in [64, 131072]; review changed engine defaults")
    if not 1 <= args.books <= 4 or not 0 <= args.orders <= 32 or not 1 <= args.tick_ticks <= 1000000:
        raise ValueError("bounds: books 1..4, orders per book 0..32, tick ticks 1..1000000")
    jdk = args.jdk or os.environ.get("JAVA_HOME")
    if not jdk and sys.platform == "darwin":
        jdk = run(["/usr/libexec/java_home"], 5).stdout.strip()
    if not jdk:
        java = shutil.which("java")
        if not java:
            raise ValueError("provide --jdk or JAVA_HOME with an installed JDK")
        jdk = str(Path(java).resolve().parent.parent)
    binaries = {name: str(Path(jdk) / "bin" / name) for name in ("java", "javac", "jar")}
    if not all(Path(p).is_file() for p in binaries.values()):
        raise ValueError("JDK must contain java, javac and jar")
    with tempfile.TemporaryDirectory(prefix="traderx-book-memory-") as disposable:
        temp = Path(disposable)
        classes = temp / "classes"
        classes.mkdir()
        units = []
        for suffix, source in selected.items():
            if suffix == "lmax/MatchingEngine.java":
                continue  # Metadata/default only: do not instantiate or compile full engine.
            target = temp / "src" / suffix
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source["bytes"])
            units.append(str(target))
        probe_bytes = (HERE / "BookMemoryProbe.java").read_bytes()
        probe = temp / "BookMemoryProbe.java"
        probe.write_bytes(probe_bytes)
        compilation = run([binaries["javac"], "-d", str(classes), *units, str(probe)])
        manifest = temp / "MANIFEST.MF"
        manifest.write_text("Manifest-Version: 1.0\nPremain-Class: finos.traderx.ordermatcher.lmax.BookMemoryProbe\n\n")
        agent = temp / "probe.jar"
        run([binaries["jar"], "cfm", str(agent), str(manifest), "-C", str(classes), "."])
        options = ["-Xms32m", "-Xmx128m", "-XX:+ExitOnOutOfMemoryError"]
        if args.references == "compressed":
            options += ["-XX:+UseCompressedOops", "-XX:+UseCompressedClassPointers"]
        elif args.references == "uncompressed":
            options += ["-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers"]
        if args.alignment:
            options += [f"-XX:ObjectAlignmentInBytes={args.alignment}"]
        command = [binaries["java"], *options, f"-javaagent:{agent}", "-cp", str(classes),
                   "finos.traderx.ordermatcher.lmax.BookMemoryProbe", str(levels), str(args.books),
                   str(args.orders), str(args.tick_ticks)]
        execution = run(command, 20)
        report = parse_report(execution.stdout)
        validate(report)
        for source in selected.values():
            if digest((root / source["path"]).read_bytes()) != source["sha256"]:
                raise ValueError("source changed during measurement")
        class_hashes = {p.relative_to(classes).as_posix(): digest(p.read_bytes()) for p in sorted(classes.rglob("*.class"))}
        report["provenance"] = {
            "state": "YU18-risk-integration", "source_mode": "direct authoritative units; no generated-tree claim",
            "checkout": str(root), "git_head": run(["git", "-C", str(root), "rev-parse", "HEAD"], 5).stdout.strip(),
            "engine_default_book_levels": default, "engine_source_compiled": False,
            "sources": {k: {a: b for a, b in v.items() if a != "bytes"} for k, v in selected.items()},
            "probe_sha256": digest(probe_bytes), "runner_sha256": digest(Path(__file__).read_bytes()),
            "class_sha256": class_hashes, "agent_jar_sha256": digest(agent.read_bytes()),
            "compile_command": [binaries["javac"], "-d", "<disposable>/classes", "<exact copied source units>", "BookMemoryProbe.java"],
            "javac_version": run([binaries["javac"], "-version"], 5).stdout.strip(),
            "compile_stderr": compilation.stderr, "jvm_stderr": execution.stderr,
            "jvm_stdout_diagnostics": [line for line in execution.stdout.splitlines() if not line.startswith("BOOK_MEMORY_JSON:")],
            "jvm_report_stdout_sha256": digest(execution.stdout.encode()),
            "jvm_command": command, "timeout_seconds": 20, "temporary_artifacts_removed_on_exit": True,
        }
        return report


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--levels", type=int, help="power of two, 64..131072; default from current MatchingEngine source")
    cli.add_argument("--books", type=int, default=2, help="1..4; default 2")
    cli.add_argument("--orders", type=int, default=4, help="pooled resting orders per book, 0..32; default 4")
    cli.add_argument("--tick-ticks", type=int, default=10000, help="1..1000000; default 10000")
    cli.add_argument("--jdk", help="installed JDK home; otherwise JAVA_HOME/system JDK")
    cli.add_argument("--references", choices=("vm-default", "compressed", "uncompressed"), default="vm-default")
    cli.add_argument("--alignment", type=int, choices=(8, 16), help="optional diagnostic VM alignment control")
    return cli


def main():
    try:
        print(json.dumps(measure(parser().parse_args()), indent=2, sort_keys=True))
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"book-memory: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
