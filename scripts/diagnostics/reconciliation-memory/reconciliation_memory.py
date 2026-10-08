#!/usr/bin/env python3
"""Bounded current orphan-sweep selected-graph diagnostic, synthetic loopback only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
PIN = "fbeeb9d27d398cc267468d570fba52c49a545abce40c11de20d4e19e79166288"
PREFIX = "generation/runtime-overrides/trade-processor/src/main/java/finos/traderx/tradeprocessor/"
OWNERS = {"service/ReconciliationService.java": "YU18-risk-integration", "service/RunRegistry.java": "YU18-risk-integration",
          "repository/TradeRepository.java": "YU18-risk-integration", "model/Trade.java": "YU18-risk-integration",
          "model/ScopedEvent.java": "YU18-risk-integration", "model/TradeOrder.java": "YU18-risk-integration",
          "model/OrderUpdate.java": "YU18-risk-integration", "model/TradeState.java": "YU16-cdm-instruments",
          "auth/JwtTokenMinter.java": "YU05-post-trade-compliance", "model/TradeSide.java": None}
DEPS = {"com.fasterxml.jackson.core/jackson-databind": "2.22.1", "com.fasterxml.jackson.core/jackson-core": "2.22.1",
        "com.fasterxml.jackson.core/jackson-annotations": "2.22", "io.micrometer/micrometer-core": "1.15.12",
        "io.micrometer/micrometer-commons": "1.15.12", "io.micrometer/micrometer-observation": "1.15.12",
        "org.slf4j/slf4j-api": "2.0.18", "org.springframework/spring-core": "6.2.19",
        "org.springframework/spring-beans": "6.2.19", "org.springframework/spring-context": "6.2.19",
        "org.springframework/spring-jcl": "6.2.19", "org.springframework/spring-jdbc": "6.2.19",
        "org.springframework/spring-tx": "6.2.19", "org.springframework.data/spring-data-jpa": "3.5.13",
        "org.springframework.data/spring-data-commons": "3.5.13", "jakarta.persistence/jakarta.persistence-api": "3.1.0", "io.swagger.core.v3/swagger-annotations-jakarta": "2.2.47"}
ANCHOR = "        OrphanSweepResult result = new OrphanSweepResult("
HOOK = "        ReconciliationMemoryProbe.capture(fullHistoryIds, localIds, orphans);\n"


def digest(data): return hashlib.sha256(data).hexdigest()


def child(command, timeout, env=None):
    result = subprocess.run(command, capture_output=True, text=True, env=env, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"diagnostic child failed({result.returncode}): {result.stderr}\n{result.stdout}")
    return result


def instrument(data):
    if digest(data) != PIN:
        raise ValueError("operative ReconciliationService SHA drift; review observer before running")
    text = data.decode()
    if text.count(ANCHOR) != 1 or HOOK in text:
        raise ValueError("observer anchor changed")
    hooked = text.replace(ANCHOR, HOOK + ANCHOR)
    if hooked.replace(HOOK, "") != text:
        raise ValueError("observer source reconstruction failed")
    return hooked.encode()


def sources(root):
    result = {}
    for suffix, owner in OWNERS.items():
        relative = Path("templates/trade-processor-specfirst/src/main/java/finos/traderx/tradeprocessor") / suffix if owner is None else Path("specs") / owner / PREFIX / suffix
        candidates = sorted(root.glob(f"specs/YU*/{PREFIX}{suffix}"))
        rank = 0 if owner is None else int(re.match(r"YU(\d+)", owner)[1])
        for path in candidates:
            newer = int(re.match(r"YU(\d+)", path.relative_to(root).parts[1])[1])
            if rank <= newer <= 18 and path != root / relative:
                raise ValueError(f"source owner requires review: {path}")
        data = (root / relative).read_bytes()
        result[suffix] = {"path": relative.as_posix(), "sha256": digest(data), "data": data,
                          "shadow_candidates": [p.relative_to(root).as_posix() for p in candidates]}
    return result


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--history", type=int, default=200, help="distinct synthetic history IDs, 0..2000")
    cli.add_argument("--orphans", type=int, default=700, help="distinct synthetic orphan IDs, 0..2000")
    cli.add_argument("--history-repeat", type=int, default=2, help="1..2 rows per history ID with increasing sequences")
    cli.add_argument("--local-repeat", type=int, default=1, help="1..2 references to each local ID")
    cli.add_argument("--page-size", type=int, default=128, help="16..256 synthetic HTTP rows per page")
    cli.add_argument("--jdk", help="installed Java21 home, JAVA_HOME or system Java21 otherwise")
    cli.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    return cli


def bounds(args):
    if not 0 <= args.history <= 2000 or not 0 <= args.orphans <= 2000 or args.history + args.orphans > 3000:
        raise ValueError("history/orphans 0..2000 and combined distinct IDs <=3000")
    if args.history_repeat not in (1, 2) or args.local_repeat not in (1, 2) or not 16 <= args.page_size <= 256:
        raise ValueError("repeats 1..2, page size 16..256")


def measure(args, root=ROOT):
    bounds(args)
    selected = sources(root)
    hooked = instrument(selected["service/ReconciliationService.java"]["data"])
    deps = {}
    for coordinate, version in DEPS.items():
        artifact = coordinate.split("/")[1]
        paths = sorted((args.cache / coordinate / version).glob(f"*/{artifact}-{version}.jar"))
        if len(paths) != 1:
            raise ValueError(f"exact offline dependency unavailable: {coordinate}:{version}")
        deps[coordinate] = {"path": str(paths[0]), "version": version, "sha256": digest(paths[0].read_bytes())}
    jdk = args.jdk or os.environ.get("JAVA_HOME")
    if not jdk and sys.platform == "darwin": jdk = child(["/usr/libexec/java_home", "-v", "21"], 5).stdout.strip()
    if not jdk: raise ValueError("installed Java21 required via --jdk or JAVA_HOME")
    bins = {n: str(Path(jdk) / "bin" / n) for n in ("java", "javac", "jar")}
    if not all(Path(p).is_file() for p in bins.values()): raise ValueError("installed JDK requires java/javac/jar")
    env = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"): env.pop(key, None)
    env.update(HTTP_PROXY="", HTTPS_PROXY="", ALL_PROXY="")
    with tempfile.TemporaryDirectory(prefix="traderx-recon-memory-") as directory:
        temp = Path(directory); classes = temp / "classes"; classes.mkdir()
        units = []
        for suffix, source in selected.items():
            path = temp / "src" / suffix; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(hooked if suffix == "service/ReconciliationService.java" else source["data"]); units.append(str(path))
        probe_data = (HERE / "ReconciliationMemoryProbe.java").read_bytes()
        probe = temp / "ReconciliationMemoryProbe.java"; probe.write_bytes(probe_data)
        cp = os.pathsep.join(d["path"] for d in deps.values())
        compile_command = [bins["javac"], "-J-Xmx128m", "-cp", cp, "-d", str(classes), *units, str(probe)]
        compilation = child(compile_command, 45, env)
        manifest = temp / "MANIFEST.MF"
        manifest.write_text("Manifest-Version: 1.0\nPremain-Class: finos.traderx.tradeprocessor.service.ReconciliationMemoryProbe\n\n")
        agent = temp / "observer.jar"; child([bins["jar"], "-J-Xmx64m", "cfm", str(agent), str(manifest), "-C", str(classes), "."], 20, env)
        command = [bins["java"], "-Xms32m", "-Xmx128m", "-XX:+ExitOnOutOfMemoryError", "--add-opens=java.base/java.util=ALL-UNNAMED",
                   "--add-opens=java.base/java.lang=ALL-UNNAMED", f"-javaagent:{agent}", "-cp", str(classes)+os.pathsep+cp,
                   "finos.traderx.tradeprocessor.service.ReconciliationMemoryProbe", str(args.history), str(args.orphans),
                   str(args.history_repeat), str(args.local_repeat), str(args.page_size)]
        execution = child(command, 35, env)
        rows = [line[len("RECON_MEMORY_JSON:"):] for line in execution.stdout.splitlines() if line.startswith("RECON_MEMORY_JSON:")]
        if len(rows) != 1: raise ValueError("expected exactly one actual orphan-sweep report")
        report = json.loads(rows[0]); validate(report)
        for source in selected.values():
            if digest((root / source["path"]).read_bytes()) != source["sha256"]: raise ValueError("source changed during measurement")
        for dep in deps.values():
            if digest(Path(dep["path"]).read_bytes()) != dep["sha256"]: raise ValueError("dependency changed during measurement")
        report["provenance"] = {"git_head": child(["git", "-C", str(root), "rev-parse", "HEAD"], 5).stdout.strip(), "checkout": str(root),
            "source_mode": "complete current service with SHA-pinned temporary observational hook; no extracted method/generated-module claim",
            "sources": {k: {a:b for a,b in v.items() if a != "data"} for k,v in selected.items()}, "dependencies": deps,
            "instrumented_service_sha256": digest(hooked), "source_reconstructs_after_hook_removal": True,
            "probe_sha256": digest(probe_data), "runner_sha256": digest(Path(__file__).read_bytes()),
            "class_sha256": {p.relative_to(classes).as_posix():digest(p.read_bytes()) for p in sorted(classes.rglob('*.class'))},
            "agent_sha256": digest(agent.read_bytes()), "compile_command": compile_command, "compile_stderr": compilation.stderr,
            "jvm_command": command, "jvm_stderr": execution.stderr, "execution_timeout_seconds":35,
            "temporary_artifacts_removed_on_exit": True, "ambient_jvm_and_proxy_environment_removed":True}
        return report


def validate(report):
    if report.get("schema") != 1 or report.get("units") != "bytes": raise ValueError("invalid report schema")
    if not report.get("actual_method_executed"): raise ValueError("actual method did not execute")
    fixture=report["fixture"]; result=report["actual_result"]; temp=report["temporary"]; witness=report["http_witness"]
    history=fixture["history_unique_requested"]; orphan_unique=fixture["orphan_unique_requested"]
    local_rows=(history+orphan_unique)*fixture["local_repeat"]; orphan_rows=orphan_unique*fixture["local_repeat"]
    cap=fixture["actual_reporting_cap"]; visible=min(cap,orphan_rows)
    if cap!=500 or not result["last_result_same_identity"] or result["local_trade_count"]!=local_rows or result["full_history_trade_count"]!=history or result["orphan_count"]!=orphan_rows:
        raise ValueError("actual method populations/cap/last-result witness mismatch")
    if len(result["reported_ids"])!=visible or any(not s.startswith("O") for s in result["reported_ids"]):
        raise ValueError("actual reported ID population mismatch")
    if witness["reindex_posts"]!=1 or witness["repository_reads"]!=1 or witness["served_history_rows"]!=history*fixture["history_repeat"]:
        raise ValueError("actual HTTP/repository path did not execute")
    expected_pages=(history*fixture["history_repeat"]+fixture["page_size"]-1)//fixture["page_size"]+1
    if witness["page_gets"]!=expected_pages: raise ValueError("history pagination incomplete")
    for key,expected in {"history_unique_ids":history,"local_id_rows":local_rows,"local_unique_ids":history+orphan_unique,"orphan_rows":orphan_rows,"orphan_unique_ids":orphan_unique}.items():
        if temp[key]!=expected: raise ValueError("temporary populations not observed")
    graphs=[temp[k] for k in ("history_selected_graph","local_ids_selected_graph","all_orphans_selected_graph","temporary_selected_union")]
    graphs += [report["last_result_selected_graph"],report["bounded_copy_selected_graph"]]
    for graph in graphs:
        if graph["selected_shallow_bytes"]<=0 or graph["unique_objects"]!=sum(c["count"] for c in graph["classes"].values()) or graph["selected_shallow_bytes"]!=sum(c["shallow_bytes"] for c in graph["classes"].values()):
            raise ValueError("invalid selected graph category/identity sum")
        if graph["array_payload_bytes"]!=sum(c["array_payload_bytes"] for c in graph["classes"].values()) or graph["array_shallow_bytes"]!=graph["array_payload_bytes"]+graph["array_base_offset_bytes"]+graph["array_alignment_padding_bytes"]:
            raise ValueError("selected array layout arithmetic mismatch")
        if graph["string_objects"]!=graph["classes"].get("java.lang.String",{}).get("count",0):
            raise ValueError("missing selected String category")
        if graph["string_storage_arrays"]!=sum(c["count"] for k,c in graph["classes"].items() if k in ("[B","[C")):
            raise ValueError("missing String backing storage")
    if graphs[0]["string_objects"]!=history or graphs[1]["string_objects"]!=history+orphan_unique or graphs[2]["string_objects"]!=orphan_unique:
        raise ValueError("temporary selected graph omits populated IDs")
    actual=report["actual_backing"]; copied=report["bounded_copy_backing"]
    if actual["visible_ids"]!=visible or actual["root_list_size"]!=visible or actual["backing_nonnull_slots"]!=visible or actual["slots_beyond_visible_view"]!=0:
        raise ValueError("bounded current result backing witness missing")
    expected_view="java.util.ArrayList"
    if actual["view_class"]!=expected_view or actual["backing_array_capacity"]<visible:
        raise ValueError("current result view/backing mechanism changed")
    if copied["visible_ids"]!=visible or copied["root_list_size"]!=visible or copied["slots_beyond_visible_view"]!=0 or copied["backing_nonnull_slots"]!=visible:
        raise ValueError("bounded-copy negative comparator not bounded")
    expected_copy_strings=len(set(result["reported_ids"]))
    if graphs[4]["string_objects"]!=expected_copy_strings or graphs[5]["string_objects"]!=expected_copy_strings:
        raise ValueError("wrong current last-result/copy graph root or retained hidden IDs")
    if orphan_rows>cap and (actual["backing_array_capacity"]!=visible or graphs[4]["selected_shallow_bytes"]!=graphs[5]["selected_shallow_bytes"]):
        raise ValueError("current capped result still retains excess backing storage")
    if temp["temporary_selected_union"]["selected_shallow_bytes"]>sum(g["selected_shallow_bytes"] for g in graphs[:3]) or (orphan_unique>0 and temp["temporary_selected_union"]["selected_shallow_bytes"]>=sum(g["selected_shallow_bytes"] for g in graphs[:3])):
        raise ValueError("temporary graph roots not identity-deduplicated")
    for name in ("full_process_or_dominator_retained_heap_bytes","all_temporary_allocation_bytes","managed_trade_row_materialization_bytes","service_http_mapper_meter_native_graph_bytes"):
        if not report["unavailable"][name]: raise ValueError("unmeasured scope must remain explicit")


def main():
    try: print(json.dumps(measure(parser().parse_args()), indent=2, sort_keys=True))
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"reconciliation-memory: {error}", file=sys.stderr); return 1
    return 0


if __name__ == "__main__": sys.exit(main())
