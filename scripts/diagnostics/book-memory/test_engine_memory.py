"""Independent current constructor/root controls, executed against real bounded engine JVMs."""
import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import engine_memory as tool

HERE = Path(__file__).resolve().parent
EVIDENCE = os.environ.get("ENGINE_MEMORY_TEST_EVIDENCE")
SMALL = ["--levels", "64", "--books", "1", "--orders", "2", "--pool", "8", "--securities", "4",
         "--terminal", "4", "--positions", "16", "--pending", "2", "--pegs", "2",
         "--accounts", "2", "--exposures", "16", "--idempotency", "8", "--references", "compressed"]


def save(name, report):
    if EVIDENCE:
        path = Path(EVIDENCE); path.mkdir(parents=True, exist_ok=True)
        (path / (name + ".json")).write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")


def sample(name, *options):
    report = tool.measure(tool.parser().parse_args([*SMALL, *options]))
    save(name, report)
    return report


class EngineMemoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.small = sample("small")
        cls.pool = sample("pool32", "--pool", "32")
        cls.levels = sample("levels128", "--levels", "128")
        cls.books = sample("books2", "--books", "2")
        cls.capacities = sample("capacities", "--securities", "8", "--terminal", "32", "--positions", "64",
                                "--pending", "16", "--accounts", "8", "--exposures", "64", "--idempotency", "32")
        cls.wide = sample("uncompressed", "--references", "uncompressed")

    def test_independent_constructor_inventory(self):
        phase = self.small["phases"][0]
        self.assertEqual(phase["categories"]["engine_arrays"]["object_count"], 16)
        self.assertEqual(phase["categories"]["engine_index_arrays"]["object_count"], 2)
        self.assertEqual(phase["categories"]["engine_position_arrays"]["object_count"], 3)
        self.assertEqual(phase["categories"]["supplied_risk_arrays"]["object_count"], 21)
        names = {a["origin"].rsplit(".", 1)[1] for a in phase["array_inventory"] if a["category"] == "engine_arrays"}
        self.assertIn("stagedExt", names)  # Field initializer, outside the visible constructor body.
        self.assertIn("triggerQueue", names)
        self.assertIn("terminalRing", names)

    def test_pool_control_changes_actual_owned_entries_only(self):
        small, large = self.small["phases"][0], self.pool["phases"][0]
        a, b = small["categories"]["engine_order_entries"], large["categories"]["engine_order_entries"]
        self.assertEqual(a["object_count"], 8); self.assertEqual(b["object_count"], 32)
        entry_size = a["shallow_bytes"] // 8
        self.assertGreater(entry_size, 0)
        self.assertEqual(b["shallow_bytes"] - a["shallow_bytes"], 24 * entry_size)
        self.assertEqual(large["measured_unique_shallow_bytes"] - small["measured_unique_shallow_bytes"], 24 * entry_size)
        for category in small["categories"]:
            if category != "engine_order_entries": self.assertEqual(small["categories"][category], large["categories"][category])

    def test_levels_and_count_controls(self):
        base = self.small["phases"][1]["categories"]
        double = self.levels["phases"][1]["categories"]
        self.assertEqual(double["book_arrays"]["array_payload_bytes"], 2 * base["book_arrays"]["array_payload_bytes"])
        many = self.books["phases"][1]["categories"]
        self.assertEqual(many["book_objects"]["object_count"], 2)
        self.assertEqual(many["book_arrays"]["shallow_bytes"], 2 * base["book_arrays"]["shallow_bytes"])

    def test_capacities_applied_to_real_arrays(self):
        def lengths(report, category):
            return {a["origin"].rsplit(".", 1)[1]: a["length"] for a in report["phases"][0]["array_inventory"] if a["category"] == category}
        small, wide = lengths(self.small, "engine_arrays"), lengths(self.capacities, "engine_arrays")
        self.assertEqual((small["terminalRing"], wide["terminalRing"]), (4, 32))
        self.assertEqual((small["triggerQueue"], wide["triggerQueue"]), (3, 17))
        self.assertEqual((small["booksBySecurity"], wide["booksBySecurity"]), (4, 8))
        self.assertEqual((lengths(self.small, "engine_position_arrays")["keys"], lengths(self.capacities, "engine_position_arrays")["keys"]), (16, 64))
        self.assertGreater(self.capacities["phases"][0]["categories"]["supplied_risk_arrays"]["array_payload_bytes"],
                           self.small["phases"][0]["categories"]["supplied_risk_arrays"]["array_payload_bytes"])

    def test_actual_pool_to_book_and_cross_no_double_count(self):
        empty, rest, cross = self.small["phases"]
        self.assertEqual([p["observed"]["free_pool_entries"] for p in (empty, rest, cross)], [8, 6, 5])
        self.assertEqual([p["categories"]["engine_order_entries"]["object_count"] for p in (empty, rest, cross)], [8, 8, 8])
        self.assertEqual(rest["observed"]["resting_orders"], 2)
        self.assertEqual(cross["observed"]["resting_orders"], 1)
        self.assertEqual(rest["measured_unique_shallow_bytes"], cross["measured_unique_shallow_bytes"])
        self.assertEqual(self.small["behavior_witness"], {"buyer_position": 1, "seller_position": -1, "first_bid_status": 2, "crossing_status": 2})
        self.assertGreater(cross["aliases_skipped"], empty["aliases_skipped"])

    def test_real_control_ordering_and_output(self):
        empty, rest, cross = self.small["phases"]
        self.assertEqual([p["observed"]["events_processed"] for p in (empty, rest, cross)], [0, 6, 7])
        self.assertEqual([p["observed"]["applied_sequence"] for p in (empty, rest, cross)], [-1, 5, 6])
        self.assertGreater(cross["observed"]["output_cursor"], rest["observed"]["output_cursor"])
        self.assertEqual(cross["observed"]["position_size"], 2)
        self.assertEqual(cross["observed"]["terminal_count"], 2)

    def test_shared_collaborator_roots_separate(self):
        for phase in self.small["phases"]:
            cats = phase["categories"]
            for key in ("supplied_risk_object", "supplied_hot_metrics_shallow", "supplied_risk_metrics_shallow", "supplied_output_publisher"):
                self.assertEqual(cats[key]["object_count"], 1)
            self.assertEqual(cats["supplied_output_slots"]["object_count"], 256)
            self.assertEqual(cats["supplied_output_typed_shapes"]["object_count"], 256)
            self.assertEqual(phase["engine_owned_unique_shallow_bytes"] + phase["supplied_measured_unique_shallow_bytes"], phase["measured_unique_shallow_bytes"])

    def test_explicit_exclusions_and_unavailable(self):
        for phase in self.small["phases"]:
            self.assertTrue(all(e["reason"] for e in phase["excluded_edges"]))
            classes = phase["reference_inventory"]
            self.assertTrue(all(r["action"] == "excluded" for r in classes["finos.traderx.ordermatcher.lmax.HotPathMetrics"]))
            self.assertTrue(all(r["action"] == "excluded" for r in classes["com.lmax.disruptor.RingBuffer"]))
        self.assertTrue(all(self.small["unavailable"].values()))

    def test_reference_width_and_source_dependency_provenance(self):
        self.assertEqual(self.wide["vm"]["flags"]["UseCompressedOops"], "false")
        self.assertEqual(self.small["vm"]["flags"]["UseCompressedOops"], "true")
        self.assertGreater(self.wide["phases"][0]["categories"]["engine_index_arrays"]["array_payload_bytes"],
                           self.small["phases"][0]["categories"]["engine_index_arrays"]["array_payload_bytes"])
        p = self.small["provenance"]
        self.assertEqual(len(p["sources"]), 16); self.assertEqual(len(p["dependencies"]), 9)
        for source in p["sources"].values(): self.assertEqual(tool.book.digest((tool.ROOT / source["path"]).read_bytes()), source["sha256"])
        for dep in p["dependencies"].values(): self.assertEqual(tool.book.digest(Path(dep["path"]).read_bytes()), dep["sha256"])
        self.assertIn("finos/traderx/ordermatcher/lmax/MatchingEngine.class", p["class_sha256"])
        self.assertEqual(self.small["vm"]["flags"]["MaxHeapSize"], str(128 * 1024 * 1024))
        self.assertEqual(self.small["fixture"]["engine_default_book_levels"], 131072)
        self.assertEqual(self.small["fixture"]["book_levels"], 64)
        self.assertEqual(self.small["fixture"]["constructor_pending_capacity"], 4096)
        self.assertEqual(self.small["fixture"]["pending_capacity"], 2)
        for report in (self.small, self.pool, self.levels, self.books, self.capacities, self.wide):
            arg = next(a for a in report["vm"]["input_arguments"] if a.startswith("-javaagent:"))
            self.assertFalse(Path(arg.split(":", 1)[1]).exists())

    def test_constructor_traffic_is_labeled_cold_sample(self):
        sample = self.small["constructor_allocation_sample"]
        self.assertGreater(sample["thread_allocated_bytes"], 0)
        self.assertGreaterEqual(sample["noop_probe_bytes"], 0)
        self.assertIn("class initialization", sample["scope"])
        self.assertIn("no baseline subtraction", sample["scope"])

    def mutant(self, name, old, new, expected=ValueError):
        original = (HERE / "EngineMemoryProbe.java").read_text()
        self.assertEqual(original.count(old), 1)
        broken = original.replace(old, new)
        with tempfile.TemporaryDirectory(prefix="engine-memory-negative-") as directory:
            alternate = Path(directory); (alternate / "EngineMemoryProbe.java").write_text(broken)
            if EVIDENCE: (Path(EVIDENCE) / (name + "-probe.java")).write_text(broken)
            validate = tool.validate
            def capture(report):
                save(name + "-report", report)
                return validate(report)
            with patch.object(tool, "HERE", alternate), patch.object(tool, "validate", capture):
                with self.assertRaises(expected) as refused:
                    tool.measure(tool.parser().parse_args(SMALL))
                if EVIDENCE:
                    (Path(EVIDENCE) / (name + "-refusal.txt")).write_text(str(refused.exception) + "\n")

    def test_real_omitted_risk_category_refused(self):
        old = "Item item = queue.remove(); Object value = item.value;"
        self.mutant("omit-risk-arrays", old, old + '\n                if (value.getClass().isArray() && "supplied_risk_arrays".equals(item.arrayCategory)) continue;')

    def test_real_shared_root_double_count_refused(self):
        old = "if (seen.put(value, true) != null) { aliases++; continue; }"
        new = "if (seen.put(value, true) != null && !(value instanceof RiskMetrics)) { aliases++; continue; }"
        self.mutant("duplicate-risk-metrics", old, new)

    def test_real_field_initializer_omission_refused(self):
        old = "for (Field f : fields) {"
        self.mutant("omit-stagedExt", old, old + '\n                    if (f.getName().equals("stagedExt")) continue;')

    def test_real_unknown_root_refused(self):
        old = 'graph.root(engine, "engine");'
        self.mutant("unknown-root", old, old + ' graph.root(new Object(), "unknown diagnostic object");', RuntimeError)

    def test_bounds_refused_before_compile(self):
        for options in (["--pool", "1"], ["--levels", "8192"], ["--books", "5"], ["--orders", "9"], ["--securities", "17"],
                        ["--terminal", "129"], ["--positions", "256"], ["--pending", "33"], ["--accounts", "1"]):
            with self.subTest(options=options), patch.object(tool, "sources", side_effect=AssertionError("must refuse before reading/building")):
                with self.assertRaises(ValueError): tool.measure(tool.parser().parse_args([*SMALL, *options]))

    def test_missing_offline_dependency_refuses_without_install(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "offline dependency"):
                tool.dependencies(Path(directory))

    def test_unexecuted_or_unapproved_excluded_scope_refused(self):
        broken = copy.deepcopy(self.small); broken["phases"] = []
        with self.assertRaises(ValueError): tool.validate(broken)
        broken = copy.deepcopy(self.small)
        row = next(r for r in broken["phases"][0]["reference_inventory"]["finos.traderx.ordermatcher.lmax.MatchingEngine"] if r["field"] == "freeList")
        row.update(action="excluded", reason="pretend exclusion")
        with self.assertRaisesRegex(ValueError, "classification"): tool.validate(broken)

    def test_java_environment_and_geometry_cannot_raise_bounds(self):
        with patch.dict(os.environ, {"BOOK_LEVELS": "999999999", "BOOK_TICK_PX": "0", "JDK_JAVA_OPTIONS": "-Xmx8g", "JAVA_TOOL_OPTIONS": "-Xmx8g", "_JAVA_OPTIONS": "-Xmx8g"}):
            report = sample("sanitized-environment")
        self.assertEqual(report["fixture"]["constructor_book_levels"], 131072)
        self.assertEqual(report["vm"]["flags"]["MaxHeapSize"], str(128*1024*1024))


if __name__ == "__main__":
    unittest.main(verbosity=2)
