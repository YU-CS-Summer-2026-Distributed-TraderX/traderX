"""Bounded real-JVM controls plus independent current-constructor inventory checks."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("book_memory", HERE / "book_memory.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
EVIDENCE = os.environ.get("BOOK_MEMORY_TEST_EVIDENCE")


def sample(name, *arguments):
    result = tool.measure(tool.parser().parse_args(list(arguments)))
    if EVIDENCE:
        path = Path(EVIDENCE)
        path.mkdir(parents=True, exist_ok=True)
        (path / (name + ".json")).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    return result


class BookMemoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.small = sample("compressed-64", "--levels", "64", "--books", "1", "--orders", "4", "--references", "compressed")
        cls.double = sample("compressed-128", "--levels", "128", "--books", "3", "--orders", "4", "--references", "compressed")
        cls.wide = sample("uncompressed-64", "--levels", "64", "--books", "1", "--orders", "4", "--references", "uncompressed")
        cls.aligned = sample("aligned16-64", "--levels", "64", "--books", "1", "--orders", "1", "--alignment", "16", "--references", "compressed")
        cls.zero = sample("empty-64", "--levels", "64", "--books", "1", "--orders", "0")
        cls.default = sample("default-levels", "--books", "2", "--orders", "32", "--references", "compressed")

    def test_independent_constructor_inventory_and_layout(self):
        # Drawn from LimitBook constructor, independent of the probe's reflective enumeration.
        book = self.small["empty_books"][0]
        arrays = {row["field"]: row for row in book["arrays"]}
        self.assertEqual(set(arrays), {"bidHead", "bidTail", "askHead", "askTail", "bidQty", "askQty", "bidBits", "askBits"})
        self.assertEqual(book["book_owned_object_count"], 9)
        self.assertEqual(book["array_payload_bytes"], 4 * 64 * 4 + 2 * 64 * 8 + 2 * 1 * 8)
        self.assertGreater(book["array_shallow_bytes"], book["array_payload_bytes"])
        self.assertGreater(book["book_object_shallow_bytes"], 0)
        self.assertEqual(book["book_owned_reachable_shallow_bytes"],
                         book["book_object_shallow_bytes"] + sum(a["shallow_bytes"] for a in arrays.values()))

    def test_levels_and_count_discriminate(self):
        small = self.small["empty_books"][0]
        larger = self.double["empty_books"][0]
        self.assertEqual(larger["array_payload_bytes"], 2 * small["array_payload_bytes"])
        self.assertEqual(self.double["fixture"]["total_book_owned_shallow_bytes"], 3 * larger["book_owned_reachable_shallow_bytes"])
        self.assertGreater(larger["book_owned_reachable_shallow_bytes"], small["book_owned_reachable_shallow_bytes"])

    def test_real_reference_width_control(self):
        compressed = self.small["empty_books"][0]
        wide = self.wide["empty_books"][0]
        self.assertEqual(self.wide["vm"]["flags"]["UseCompressedOops"], "false")
        self.assertEqual(self.small["vm"]["flags"]["UseCompressedOops"], "true")
        self.assertEqual(wide["array_payload_bytes"] - compressed["array_payload_bytes"], 4 * 64 * 4)
        self.assertEqual({a["element_bytes"] for a in wide["arrays"] if a["field"].endswith("Head")}, {8})

    def test_alignment_control(self):
        self.assertEqual(self.aligned["vm"]["flags"]["ObjectAlignmentInBytes"], "16")
        for book in self.aligned["occupied_books"]:
            self.assertEqual(book["book_object_shallow_bytes"] % 16, 0)
            self.assertTrue(all(a["shallow_bytes"] % 16 == 0 for a in book["arrays"]))

    def test_actual_default_and_modest_nonempty_fixture(self):
        self.assertEqual(self.default["provenance"]["engine_default_book_levels"], 1 << 17)
        self.assertEqual(self.default["fixture"]["levels"], 1 << 17)
        self.assertEqual(self.default["empty_books"][0]["array_payload_bytes"], 4227072)
        self.assertEqual(self.default["occupied_books"][0]["open_orders"], 32)

    def test_pool_ownership_and_identity_dedup(self):
        empty, used = self.small["empty_books"][0], self.small["occupied_books"][0]
        self.assertEqual(empty["book_owned_reachable_shallow_bytes"], used["book_owned_reachable_shallow_bytes"])
        self.assertEqual(used["shared_pool_reachable_order_count"], 4)  # 2 heads + 2 tails + links all alias these four.
        self.assertGreater(used["shared_pool_reachable_order_shallow_bytes"], 0)
        self.assertGreater(self.small["fixture"]["pool_entries_shallow_bytes"], used["shared_pool_reachable_order_shallow_bytes"])
        self.assertEqual(used["book_and_shared_reachable_union_shallow_bytes"] - empty["book_and_shared_reachable_union_shallow_bytes"],
                         used["shared_pool_reachable_order_shallow_bytes"])
        self.assertEqual(self.zero["occupied_books"][0]["shared_pool_reachable_order_shallow_bytes"], 0)
        self.assertEqual(self.aligned["occupied_books"][0]["shared_pool_reachable_order_count"], 1)

    def test_exact_source_and_compilation_provenance(self):
        p = self.small["provenance"]
        self.assertEqual(len(p["sources"]), 7)
        self.assertFalse(p["engine_source_compiled"])
        self.assertIn("finos/traderx/ordermatcher/lmax/LimitBook.class", p["class_sha256"])
        self.assertIn("finos/traderx/ordermatcher/lmax/RestingOrder.class", p["class_sha256"])
        for source in p["sources"].values():
            self.assertEqual(source["sha256"], tool.digest((tool.ROOT / source["path"]).read_bytes()))
        self.assertEqual(self.small["vm"]["flags"]["MaxHeapSize"], str(128 * 1024 * 1024))
        self.assertTrue(p["temporary_artifacts_removed_on_exit"])
        agent_arg = next(a for a in self.small["vm"]["input_arguments"] if a.startswith("-javaagent:"))
        self.assertFalse(Path(agent_arg.split(":", 1)[1]).exists())

    def test_unavailable_categories_stay_explicit(self):
        for category in ("retained_heap_bytes", "constructor_allocated_bytes", "production_pool_output_engine_bytes"):
            self.assertTrue(self.small["unavailable"][category])

    def test_each_array_omission_refused(self):
        for i in range(8):
            with self.subTest(array=i):
                altered = copy.deepcopy(self.small)
                altered["empty_books"][0]["arrays"].pop(i)
                with self.assertRaisesRegex(ValueError, "inventory"):
                    tool.validate(altered)

    def test_missing_object_or_pool_category_refused(self):
        for name in ("book_object_shallow_bytes", "shared_pool_reachable_order_shallow_bytes"):
            with self.subTest(category=name):
                altered = copy.deepcopy(self.small)
                altered["occupied_books"][0][name] = 0
                with self.assertRaises(ValueError):
                    tool.validate(altered)

    def test_nonexecuted_fixture_refused(self):
        altered = copy.deepcopy(self.small)
        altered["occupied_books"] = []
        with self.assertRaisesRegex(ValueError, "execute"):
            tool.validate(altered)

    def test_recomputed_omission_in_actual_probe_refused(self):
        # Rebuild an intentionally broken observer. All its own totals remain internally consistent.
        # The independently sourced inventory still rejects the real JVM report.
        original = (HERE / "BookMemoryProbe.java").read_text()
        marker = "for (Field field : references(LimitBook.class)) {"
        self.assertEqual(original.count(marker), 1)
        broken = original.replace(marker, marker + '\n            if (field.getName().equals("askBits")) continue;')
        with tempfile.TemporaryDirectory(prefix="book-memory-negative-") as directory:
            alternate = Path(directory)
            (alternate / "BookMemoryProbe.java").write_text(broken)
            original_validate = tool.validate

            def capture_negative(report):
                if EVIDENCE:
                    path = Path(EVIDENCE)
                    (path / "omitted-array-negative-report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
                    (path / "omitted-array-negative-probe.java").write_text(broken)
                return original_validate(report)

            with patch.object(tool, "HERE", alternate), patch.object(tool, "validate", capture_negative):
                with self.assertRaisesRegex(ValueError, "inventory"):
                    tool.measure(tool.parser().parse_args(["--levels", "64", "--books", "1"]))

    def test_bad_bounds_refused_before_child(self):
        for arguments in (["--books", "0"], ["--books", "5"], ["--levels", "63"], ["--levels", "65"],
                          ["--levels", "262144"], ["--orders", "-1"], ["--orders", "33"], ["--tick-ticks", "0"]):
            with self.subTest(arguments=arguments):
                with patch.object(tool, "run", side_effect=AssertionError("should not start child")):
                    with self.assertRaises(ValueError):
                        tool.measure(tool.parser().parse_args(arguments))

    def test_ambiguous_or_changed_default_refused(self):
        for source in ("", "public static final int DEFAULT_BOOK_LEVELS = 4096;",
                       "public static final int DEFAULT_BOOK_LEVELS = 1 << 17;" * 2):
            with self.assertRaises(ValueError):
                tool.default_levels(source)

    def test_child_timeout_is_failure(self):
        with self.assertRaises(subprocess.TimeoutExpired):
            tool.run([os.sys.executable, "-c", "import time; time.sleep(10)"], .05)

    def test_report_framing_refuses_absent_duplicate_and_corrupt(self):
        for stdout in ("", "[warning] diagnostic only", "BOOK_MEMORY_JSON:{}\nBOOK_MEMORY_JSON:{}", "BOOK_MEMORY_JSON:broken"):
            with self.assertRaises(ValueError):
                tool.parse_report(stdout)
        self.assertEqual(tool.parse_report('[warning] CDS disabled\nBOOK_MEMORY_JSON:{"schema":1}'), {"schema": 1})


if __name__ == "__main__":
    unittest.main(verbosity=2)
