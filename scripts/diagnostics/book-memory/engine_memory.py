#!/usr/bin/env python3
"""Bounded real MatchingEngine component object accounting; installed/offline tools only."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import book_memory as book

ROOT = book.ROOT
HERE = Path(__file__).resolve().parent
OWNERS = dict(book.OWNERS, **{
    "lmax/PositionBook.java": "YU02-lmax-kubernetes",
    "lmax/CpuAffinity.java": "YU02-lmax-kubernetes",
    "lmax/HotPathMetrics.java": "YU03-in-memory-risk-gateway",
    "lmax/OutputPublisher.java": "YU18-risk-integration",
    "lmax/OutputEvent.java": "YU18-risk-integration",
    "risk/BlpRiskState.java": "YU18-risk-integration",
    "risk/RiskReason.java": "YU18-risk-integration",
    "risk/RiskMetrics.java": "YU04-durable-control-feeds",
    "risk/GatewayReplicaStore.java": "YU04-durable-control-feeds",
})
DEPENDENCIES = {
    "org.agrona/agrona": "2.4.1", "com.lmax/disruptor": "4.0.0",
    "org.hdrhistogram/HdrHistogram": "2.2.2", "net.openhft/affinity": "3.23.3",
    "org.slf4j/slf4j-api": "2.0.18", "org.springframework/spring-context": "6.2.19",
    "org.springframework/spring-beans": "6.2.19", "org.springframework/spring-core": "6.2.19",
    "jakarta.annotation/jakarta.annotation-api": "2.1.1",
}


def sources(root):
    result = {}
    for suffix, owner in OWNERS.items():
        path = root / "specs" / owner / book.PREFIX / suffix
        candidates = sorted(root.glob(f"specs/YU*/{book.PREFIX}{suffix}"))
        rank = int(re.match(r"YU(\d+)", owner)[1])
        for candidate in candidates:
            newer = int(re.match(r"YU(\d+)", candidate.relative_to(root).parts[1])[1])
            if rank <= newer <= 18 and candidate != path:
                raise ValueError(f"source owner needs review: {candidate}")
        data = path.read_bytes()
        result[suffix] = {"path": path.relative_to(root).as_posix(), "sha256": book.digest(data), "data": data,
                          "shadow_candidates": [p.relative_to(root).as_posix() for p in candidates]}
    return result


def dependencies(cache):
    result = {}
    for coordinate, version in DEPENDENCIES.items():
        artifact = coordinate.split("/")[1]
        paths = sorted((cache / coordinate / version).glob(f"*/{artifact}-{version}.jar"))
        if len(paths) != 1:
            raise ValueError(f"exact offline dependency unavailable/ambiguous: {coordinate}:{version}")
        path = paths[0]
        result[coordinate] = {"version": version, "path": str(path), "sha256": book.digest(path.read_bytes())}
    return result


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    for name, default in [("levels", 128), ("books", 2), ("orders", 2), ("pool", 16),
                          ("securities", 8), ("terminal", 16), ("positions", 32), ("pending", 8),
                          ("pegs", 8), ("accounts", 8), ("exposures", 64), ("idempotency", 32)]:
        cli.add_argument("--" + name, type=int, default=default)
    cli.add_argument("--jdk", help="installed JDK home; JAVA_HOME or system JDK otherwise")
    cli.add_argument("--references", choices=("compressed", "uncompressed", "vm-default"), default="vm-default")
    cli.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1",
                     help="existing offline Gradle modules cache; no resolution or downloads")
    return cli


def check_bounds(args):
    limits = {"books": (1, 4), "orders": (1, 8), "pool": (8, 128), "securities": (4, 16),
              "terminal": (4, 128), "positions": (16, 128), "pending": (1, 32), "pegs": (1, 32),
              "accounts": (2, 16), "exposures": (16, 256), "idempotency": (8, 128)}
    if not 64 <= args.levels <= 4096 or args.levels & (args.levels - 1):
        raise ValueError("fixture levels must be a power of two in 64..4096")
    for name, (lower, upper) in limits.items():
        if not lower <= getattr(args, name) <= upper:
            raise ValueError(f"{name} fixture bound is {lower}..{upper}")
    if args.securities < args.books or args.pool < args.books * args.orders + 1:
        raise ValueError("fixture needs one security per book and pool >= resting orders + one crossing order")


def child(command, timeout, env):
    result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"diagnostic child failed({result.returncode}): {result.stderr}\n{result.stdout}")
    return result


def measure(args, root=ROOT):
    check_bounds(args)
    selected = sources(root)
    deps = dependencies(args.cache)
    jdk = args.jdk or os.environ.get("JAVA_HOME")
    if not jdk and sys.platform == "darwin":
        jdk = book.run(["/usr/libexec/java_home", "-v", "21"], 5).stdout.strip()
    if not jdk:
        raise ValueError("provide an installed JDK with --jdk or JAVA_HOME")
    bins = {name: str(Path(jdk) / "bin" / name) for name in ("java", "javac", "jar")}
    if not all(Path(p).is_file() for p in bins.values()):
        raise ValueError("installed JDK requires java/javac/jar")
    removed = ["BOOK_LEVELS", "BOOK_TICK_PX", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]
    env = dict(os.environ)
    for variable in removed:
        env.pop(variable, None)
    with tempfile.TemporaryDirectory(prefix="traderx-engine-memory-") as directory:
        temp = Path(directory); classes = temp / "classes"; classes.mkdir()
        units = []
        for suffix, source in selected.items():
            path = temp / "src" / suffix; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(source["data"]); units.append(str(path))
        probe = temp / "EngineMemoryProbe.java"
        probe_data = (HERE / "EngineMemoryProbe.java").read_bytes(); probe.write_bytes(probe_data)
        classpath = os.pathsep.join(d["path"] for d in deps.values())
        compilation = child([bins["javac"], "-J-Xmx128m", "-J-XX:+ExitOnOutOfMemoryError", "-cp", classpath, "-d", str(classes), *units, str(probe)], 45, env)
        manifest = temp / "MANIFEST.MF"
        manifest.write_text("Manifest-Version: 1.0\nPremain-Class: finos.traderx.ordermatcher.lmax.EngineMemoryProbe\n\n")
        agent = temp / "engine-probe.jar"
        child([bins["jar"], "-J-Xmx64m", "cfm", str(agent), str(manifest), "-C", str(classes), "."], 20, env)
        options = ["-Xms32m", "-Xmx128m", "-XX:+ExitOnOutOfMemoryError",
                   "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                   "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"]
        if args.references != "vm-default":
            flag = "+" if args.references == "compressed" else "-"
            options += [f"-XX:{flag}UseCompressedOops", f"-XX:{flag}UseCompressedClassPointers"]
        fixture_args = [str(getattr(args, key)) for key in
                        ("levels", "books", "orders", "pool", "securities", "terminal", "positions", "pending", "pegs", "accounts", "exposures", "idempotency")]
        command = [bins["java"], *options, f"-javaagent:{agent}", "-cp", str(classes) + os.pathsep + classpath,
                   "finos.traderx.ordermatcher.lmax.EngineMemoryProbe", *fixture_args]
        execution = child(command, 30, env)
        report = book.parse_report(execution.stdout)
        validate(report)
        if report["fixture"]["engine_default_book_levels"] != book.default_levels(selected["lmax/MatchingEngine.java"]["data"].decode()):
            raise ValueError("engine default/source provenance mismatch")
        for source in selected.values():
            if book.digest((root / source["path"]).read_bytes()) != source["sha256"]:
                raise ValueError("source changed during engine measurement")
        for dependency in deps.values():
            if book.digest(Path(dependency["path"]).read_bytes()) != dependency["sha256"]:
                raise ValueError("dependency changed during engine measurement")
        report["provenance"] = {
            "source_mode": "direct exact authoritative units; no generated module claim",
            "shared_book_helper_sha256": book.digest(Path(book.__file__).read_bytes()),
            "checkout": str(root), "git_head": book.run(["git", "-C", str(root), "rev-parse", "HEAD"], 5).stdout.strip(),
            "sources": {k: {a: b for a, b in v.items() if a != "data"} for k, v in selected.items()},
            "dependencies": deps, "probe_sha256": book.digest(probe_data), "runner_sha256": book.digest(Path(__file__).read_bytes()),
            "class_sha256": {p.relative_to(classes).as_posix(): book.digest(p.read_bytes()) for p in sorted(classes.rglob("*.class"))},
            "agent_sha256": book.digest(agent.read_bytes()), "javac_version": child([bins["javac"], "-J-Xmx128m", "-version"], 5, env).stdout.strip(),
            "compile_stderr": compilation.stderr, "jvm_stderr": execution.stderr,
            "jvm_stdout_diagnostics": [line for line in execution.stdout.splitlines() if not line.startswith("BOOK_MEMORY_JSON:")],
            "jvm_command": command, "execution_timeout_seconds": 30, "temporary_artifacts_removed_on_exit": True,
            "environment_removed": removed, "compiler_max_heap_bytes": 128 * 1024 * 1024, "jar_max_heap_bytes": 64 * 1024 * 1024,
        }
        return report


def validate(report):
    if report.get("schema") != 1 or report.get("profile") != "engine-components" or report.get("units") != "bytes":
        raise ValueError("invalid engine report schema")
    if len(report.get("phases", [])) != 3:
        raise ValueError("engine fixture phases did not execute")
    fixture = report["fixture"]
    # Independent inventory from current constructor and stagedExt field initializer.
    engine_arrays = {"booksBySecurity", "bookTickPxBySecurity", "lastPxBySecurity", "terminalRing", "pendHead",
                     "pendTail", "pendCount", "pegHead", "pegTail", "pegCount", "lastTradePx", "hasTraded",
                     "pegRefBid", "pegRefAsk", "triggerQueue", "stagedExt"}
    engine_refs = engine_arrays | {"out", "metrics", "risk", "ordersByRef", "freeList", "positions", "snapshotTrigger"}
    risk_arrays = {"accountIds", "accountEnabled", "selfMatchGroups", "reservedNotional", "reservedBuyNotional",
                   "reservedSellNotional", "executedNotional", "securityEnabled", "securityRestricted", "lastPrice",
                   "lastPriceTime", "contractMultiplier", "idempotencyKeys", "idempotencyOrderRefs", "idempotencyDecisions",
                   "idempotencyRetentionKeys", "entitlementKeys", "entitlementEnabled", "exposureKeys",
                   "reservedBuyQtyByExposure", "reservedSellQtyByExposure"}
    prefix = "finos.traderx.ordermatcher."
    contracts = {
        prefix + "lmax.MatchingEngine": engine_refs,
        prefix + "lmax.PositionBook": {"keys", "values", "avgCostTicks"},
        prefix + "lmax.RestingOrder": {"storeNext", "storePrev", "bookNext", "bookPrev", "nextFree"},
        prefix + "risk.BlpRiskState": risk_arrays | {"metrics"},
        prefix + "lmax.OutputPublisher": {"ring", "onBackpressure"},
        prefix + "lmax.OutputEvent": {"typed"}, prefix + "lmax.OutputEvent$TypedShape": set(),
        "org.agrona.collections.Int2ObjectHashMap": {"keys", "values", "valueCollection", "keySet", "entrySet"},
        prefix + "lmax.HotPathMetrics": {"journalNs", "blpEventNs", "matchNs", "egressNs", "riskDecisionNs", "projectorBatchRows", "backpressureWaits"},
        prefix + "risk.RiskMetrics": {"gatewayRejects", "authoritativeDecisions", "duplicates", "gaps", "mismatches", "rebootstrap",
                                      "controlRejected", "policyVersion", "sourceVersion", "highWatermark", "gatewayValidationNs", "sourceWatermarks", "quarantineCounts"},
    }
    required = {"engine_object": 1, "engine_arrays": 16, "engine_index_object": 1, "engine_index_arrays": 2,
                "engine_order_entries": fixture["initial_pool_entries"], "engine_position_object": 1,
                "engine_position_arrays": 3, "supplied_risk_object": 1, "supplied_risk_arrays": 21,
                "supplied_output_publisher": 1, "supplied_hot_metrics_shallow": 1, "supplied_risk_metrics_shallow": 1,
                "supplied_output_ring_shallow_boundary": 1, "supplied_output_slots": fixture["output_ring_slots"],
                "supplied_output_typed_shapes": fixture["output_ring_slots"]}
    names = ["empty", "resting", "crossed"]
    for number, phase in enumerate(report["phases"]):
        if phase["phase"] != names[number]:
            raise ValueError("wrong engine phase order")
        cats = phase["categories"]
        counts = dict(required)
        if number:
            counts.update(book_objects=fixture["book_count"], book_arrays=8 * fixture["book_count"])
        if set(cats) != set(counts) or any(cats[k]["object_count"] != n for k, n in counts.items()):
            raise ValueError("missing/duplicated engine or supplied category")
        if any(c["shallow_bytes"] <= 0 for c in cats.values()):
            raise ValueError("nonpositive measured category")
        if phase["measured_unique_shallow_bytes"] != sum(c["shallow_bytes"] for c in cats.values()):
            raise ValueError("invalid unique shallow total")
        if phase["measured_unique_object_count"] != sum(c["object_count"] for c in cats.values()):
            raise ValueError("unique object count/dedup mismatch")
        owned = sum(c["shallow_bytes"] for k, c in cats.items() if k.startswith(("engine_", "book_")))
        if phase["engine_owned_unique_shallow_bytes"] != owned or phase["supplied_measured_unique_shallow_bytes"] != phase["measured_unique_shallow_bytes"] - owned:
            raise ValueError("ownership total mismatch")
        contracts_now = dict(contracts)
        if number:
            contracts_now[prefix + "lmax.LimitBook"] = {"bidHead", "bidTail", "askHead", "askTail", "bidQty", "askQty", "bidBits", "askBits"}
        inventories = phase["reference_inventory"]
        if set(inventories) != set(contracts_now) | {"com.lmax.disruptor.RingBuffer"}:
            raise ValueError("missing root/class reference inventory")
        for cls, expected in contracts_now.items():
            rows = inventories[cls]
            if len(rows) != len(expected) or {r["field"] for r in rows} != expected:
                raise ValueError("incomplete constructor/reference inventory")
            for row in rows:
                expected_action = "follow"
                if cls.endswith(("HotPathMetrics", "RiskMetrics")) or (cls.startswith("org.agrona") and row["field"] in {"valueCollection", "keySet", "entrySet"}):
                    expected_action = "excluded"
                elif row["field"] in {"snapshotTrigger", "onBackpressure"}:
                    expected_action = "require_null"
                if row["action"] != expected_action or (expected_action == "excluded" and not row["reason"]):
                    raise ValueError("unapproved reference exclusion/classification")
        for row in inventories["com.lmax.disruptor.RingBuffer"]:
            if row["action"] != "excluded" or not row["reason"]:
                raise ValueError("ring boundary exclusion missing")
        if not phase["excluded_edges"] or any(not r["reason"] for r in phase["excluded_edges"]):
            raise ValueError("unexplained graph exclusion")
        arrays = phase["array_inventory"]
        for category_name, expected_fields in [("engine_arrays", engine_arrays), ("supplied_risk_arrays", risk_arrays),
                                               ("engine_position_arrays", {"keys", "values", "avgCostTicks"}),
                                               ("engine_index_arrays", {"keys", "values"})]:
            rows = [a for a in arrays if a["category"] == category_name]
            if len(rows) != len(expected_fields) or {a["origin"].rsplit(".", 1)[1] for a in rows} != expected_fields:
                raise ValueError("incomplete owned/supplied array category")
        book_rows = [a for a in arrays if a["category"] == "book_arrays"]
        if number:
            expected_fields = {"bidHead", "bidTail", "askHead", "askTail", "bidQty", "askQty", "bidBits", "askBits"}
            for name in expected_fields:
                matching = [a for a in book_rows if a["origin"].endswith("." + name)]
                length = fixture["book_levels"] >> 6 if name.endswith("Bits") else fixture["book_levels"]
                if len(matching) != fixture["book_count"] or any(a["length"] != length for a in matching):
                    raise ValueError("incomplete/wrong current book-array geometry")
        for array in arrays:
            if array["element_bytes"] <= 0 or array["payload_bytes"] != array["length"] * array["element_bytes"]:
                raise ValueError("payload arithmetic mismatch")
            if array["base_offset_bytes"] <= 0 or array["alignment_padding_bytes"] < 0 or array["shallow_bytes"] != array["payload_bytes"] + array["base_offset_bytes"] + array["alignment_padding_bytes"]:
                raise ValueError("array shallow/layout mismatch")
        for key, category in cats.items():
            rows = [a for a in arrays if a["category"] == key]
            if category["array_payload_bytes"] != sum(a["payload_bytes"] for a in rows):
                raise ValueError("category payload sum mismatch")
            if rows and (category["object_count"] != len(rows) or category["shallow_bytes"] != sum(a["shallow_bytes"] for a in rows)):
                raise ValueError("category array sum mismatch")
        engine_lengths = {a["origin"].rsplit(".", 1)[1]: a["length"] for a in arrays if a["category"] == "engine_arrays"}
        if engine_lengths["terminalRing"] != fixture["terminal_retention"] or engine_lengths["triggerQueue"] != fixture["pending_capacity"] + 1:
            raise ValueError("terminal/trigger capacities not applied")
        if any(engine_lengths[field] != fixture["max_securities"] for field in engine_arrays - {"terminalRing", "triggerQueue", "stagedExt"}):
            raise ValueError("security capacities not applied")
        observed = phase["observed"]
        if observed["engine_book_levels"] != fixture["book_levels"] or observed["engine_global_tick_ticks"] != fixture["global_tick_ticks"] or observed["pending_capacity"] != fixture["pending_capacity"] or observed["peg_capacity"] != fixture["peg_capacity"]:
            raise ValueError("actual engine fixture geometry/capacities mismatch")
        resting = fixture["book_count"] * fixture["orders_per_book"]
        expected_index = 0 if number == 0 else resting + (number == 2)
        if observed["order_index_size"] != expected_index or observed["free_pool_entries"] + expected_index != fixture["initial_pool_entries"]:
            raise ValueError("pool-to-index transfer/dedup mismatch")
        if observed["book_count"] != (0 if number == 0 else fixture["book_count"]) or observed["resting_orders"] != (0 if number == 0 else resting - (number == 2)):
            raise ValueError("real book population witness mismatch")
        expected_events = 0 if number == 0 else 2 + 2 * fixture["book_count"] + resting + (number == 2)
        if observed["events_processed"] != expected_events or observed["applied_sequence"] != expected_events - 1:
            raise ValueError("real engine control/order sequence not executed")
        if observed["position_size"] != (2 if number == 2 else 0) or observed["terminal_count"] != (2 if number == 2 else 0):
            raise ValueError("real crossing position/terminal witness mismatch")
    if report["behavior_witness"] != {"buyer_position": 1, "seller_position": -1, "first_bid_status": 2, "crossing_status": 2}:
        raise ValueError("core crossing/ordering behavior witness missing")
    empty, resting, crossed = report["phases"]
    for category in required:
        if empty["categories"][category] != resting["categories"][category] or resting["categories"][category] != crossed["categories"][category]:
            raise ValueError("preallocated engine/supplied category changed across pool-to-book/cross")
    if resting["measured_unique_shallow_bytes"] - empty["measured_unique_shallow_bytes"] != resting["categories"]["book_objects"]["shallow_bytes"] + resting["categories"]["book_arrays"]["shallow_bytes"]:
        raise ValueError("pool shared orders counted again in book growth")
    if report["constructor_allocation_sample"]["thread_allocated_bytes"] <= 0 or report["constructor_allocation_sample"]["noop_probe_bytes"] < 0:
        raise ValueError("constructor allocation window not measured")
    if fixture["constructor_book_levels"] != fixture["engine_default_book_levels"]:
        raise ValueError("inherited environment contaminated engine geometry")
    for name in ("retained_heap_bytes", "full_member_heap_bytes", "metrics_descendant_bytes", "ring_infrastructure_descendant_bytes"):
        if not report["unavailable"][name]:
            raise ValueError("measurement exclusion must remain explicit")


def main():
    try:
        print(json.dumps(measure(parser().parse_args()), indent=2, sort_keys=True))
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"engine-memory: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
