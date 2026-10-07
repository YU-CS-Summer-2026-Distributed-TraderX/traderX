"""Offline configuration composition and bounded real-JVM heap-OOM proof.

Requires Python 3, Ruby/Psych, kubectl (kustomize only), Docker Compose
(config only), and JDK 21 selected by JAVA_HOME. Never starts a container.
RI24_RUNTIME_ROOT can select a separately generated runtime; RI24_EVIDENCE_DIR
captures commands, child output and a nonzero-count JUnit report.
"""
import copy
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
PACK = ROOT / "specs/YU18-risk-integration"
RUNTIME = Path(os.environ.get("RI24_RUNTIME_ROOT", ROOT / "generated/code/target-generated"))
OWNER = PACK / "generation/runtime-overrides"
FLAG = "-XX:+ExitOnOutOfMemoryError"
BASE_REL = "kubernetes-runtime/manifests/base/trade-processor-deployment.yaml"
COMMANDS = []


def run(command, *, env=None, input=None, timeout=30):
    # subprocess.run kills and waits for its owned child on timeout. No daemons.
    result = subprocess.run([str(x) for x in command], env=env, input=input,
                            text=True, capture_output=True, timeout=timeout)
    COMMANDS.append({"command": [str(x) for x in command], "returncode": result.returncode,
                     "stdout": result.stdout, "stderr": result.stderr})
    return result


def checked(command, **kwargs):
    result = run(command, **kwargs)
    if result.returncode:
        raise AssertionError(f"command failed: {command}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def yaml_documents(text):
    # Use the available real YAML parser, not a textual env/substring check.
    return json.loads(checked(["ruby", "-rjson", "-ryaml", "-e",
                              "puts JSON.generate(YAML.load_stream(STDIN.read))"], input=text))


def container(text):
    deployments = [d for d in yaml_documents(text) if isinstance(d, dict)
                   and d.get("kind") == "Deployment"
                   and d.get("metadata", {}).get("name") == "trade-processor"]
    if len(deployments) != 1:
        raise AssertionError(f"expected one trade-processor Deployment, got {len(deployments)}")
    containers = [c for c in deployments[0]["spec"]["template"]["spec"]["containers"]
                  if c["name"] == "trade-processor"]
    if len(containers) != 1:
        raise AssertionError("expected one named trade-processor container")
    return containers[0]


def entrypoint(path):
    lines = [line for line in path.read_text().splitlines() if line.startswith("ENTRYPOINT ")]
    if len(lines) != 1:
        raise AssertionError("expected one exec ENTRYPOINT")
    return json.loads(lines[0].split(" ", 1)[1])


def jvm_env(c):
    entries = [e for e in c.get("env", []) if e["name"] == "JAVA_TOOL_OPTIONS"]
    if len(entries) > 1:
        raise AssertionError("duplicate JAVA_TOOL_OPTIONS")
    return entries[0]["value"] if entries else ""


class ProjectorOomExitTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for tool in ("ruby", "kubectl", "docker"):
            if not shutil.which(tool):
                raise AssertionError(f"required offline tool missing: {tool}")
        home = os.environ.get("JAVA_HOME")
        if not home:
            raise AssertionError("select JDK 21 using JAVA_HOME")
        cls.java = Path(home) / "bin/java"
        version = checked([cls.java, "--version"])
        if not version.splitlines()[0].split()[1].startswith("21."):
            raise AssertionError(f"this proof requires Java 21: {version}")
        cls.temp = tempfile.TemporaryDirectory(prefix="ri24-heap-oom-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.scratch = Path(cls.temp.name)
        checked([Path(home) / "bin/javac", "--release", "21", "-d", cls.scratch,
                 Path(__file__).with_name("HeapOomFixture.java")])
        cls.jar = cls.scratch / "fixture.jar"
        checked([Path(home) / "bin/jar", "--create", "--file", cls.jar,
                 "--main-class", "HeapOomFixture", "-C", cls.scratch, "HeapOomFixture.class"])
        cls.base = checked(["kubectl", "kustomize", RUNTIME / "kubernetes-runtime/manifests/base"])
        cls.kind = checked(["kubectl", "kustomize", ROOT / "specs/YU17-otc-rates/generation/kubernetes/cluster"])
        cls.gke = checked(["kubectl", "kustomize", ROOT / "specs/YU17-otc-rates/generation/kubernetes/cluster/gke"])
        cls.compose = json.loads(checked(["docker", "compose", "-f",
            RUNTIME / "pricing-awareness-market-data/docker-compose.yml", "config", "--format", "json"]))
        patch = json.loads((PACK / "generation/kubernetes/gke-demo/trade-processor.patch.json").read_text())
        cls.patch = copy.deepcopy(patch)
        patch.update(apiVersion="apps/v1", kind="Deployment",
                     metadata={"name": "trade-processor", "namespace": "traderx"})
        (cls.scratch / "base.yaml").write_text(cls.gke)
        (cls.scratch / "patch.json").write_text(json.dumps(patch))
        (cls.scratch / "kustomization.yaml").write_text(
            "apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\n"
            "resources: [base.yaml]\npatches:\n  - path: patch.json\n")
        cls.demo = checked(["kubectl", "kustomize", cls.scratch])

    def launch(self, launch, options="", mode=None):
        self.assertEqual(launch[0], "java")
        self.assertEqual(launch[-2:], ["-jar", "/opt/app/app.jar"])
        # Preserve all effective JVM arguments/env; replace only executable and app
        # jar with the owned disposable fixture. -Xmx16m bounds the experiment.
        command = [self.java, *launch[1:-2], "-Xms16m", "-Xmx16m", "-jar", self.jar]
        if mode:
            command.append(mode)
        env = os.environ.copy()
        for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            env.pop(name, None)
        if options:
            env["JAVA_TOOL_OPTIONS"] = options
        return run(command, env=env, timeout=15)

    def assert_exit(self, result):
        output = result.stdout + result.stderr
        self.assertGreater(result.returncode, 0, output)  # excludes OS signals
        self.assertIn("EXIT_FLAG=true", output)
        self.assertIn("Terminating due to java.lang.OutOfMemoryError: Java heap space", output)
        self.assertNotIn("HEAP_OOM_CAUGHT", output)
        self.assertNotIn("POST_CATCH_ALIVE", output)

    def test_baseline_catches_real_heap_oom_and_continues(self):
        result = self.launch(["java", "-jar", "/opt/app/app.jar"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("EXIT_FLAG=false", result.stdout)
        self.assertIn("HEAP_OOM_CAUGHT: Java heap space", result.stdout)
        self.assertIn("POST_CATCH_ALIVE", result.stdout)

    def test_baseline_full_runtime_preserved_options_still_continues(self):
        result = self.launch(["java", "-jar", "/opt/app/app.jar"], "-XX:MaxRAMPercentage=75")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("MAX_RAM_PERCENT=75.0", result.stdout)
        self.assertIn("POST_CATCH_ALIVE", result.stdout)

    def test_source_plain_image_exits(self):
        self.assert_exit(self.launch(entrypoint(OWNER / "trade-processor/Dockerfile")))

    def test_source_compose_image_exits(self):
        self.assert_exit(self.launch(entrypoint(OWNER / "trade-processor/Dockerfile.compose")))

    def test_generated_compose_build_launch_exits(self):
        config = self.compose["services"]["trade-processor"]
        # Compose normalizes omitted commands to null; an explicit empty list
        # would disable the image entrypoint and must still fail this check.
        self.assertIsNone(config.get("entrypoint"))
        self.assertIsNone(config.get("command"))
        self.assertEqual(Path(config["build"]["context"]), RUNTIME / "trade-processor")
        self.assertEqual(config["build"]["dockerfile"], "Dockerfile.compose")
        self.assert_exit(self.launch(entrypoint(RUNTIME / "trade-processor" / config["build"]["dockerfile"]),
                                     config["environment"].get("JAVA_TOOL_OPTIONS", "")))

    def test_generated_full_runtime_launch_exits(self):
        c = container(self.base)
        self.assertNotIn("command", c)
        self.assertNotIn("args", c)
        result = self.launch(entrypoint(RUNTIME / "trade-processor/Dockerfile"), jvm_env(c))
        self.assert_exit(result)
        self.assertIn("MAX_RAM_PERCENT=75.0", result.stdout)

    def test_kind_cluster_image_launch_exits(self):
        c = container(self.kind)
        self.assertNotIn("command", c)
        self.assertNotIn("args", c)
        self.assert_exit(self.launch(entrypoint(RUNTIME / "trade-processor/Dockerfile"), jvm_env(c)))

    def test_gke_cluster_rebuilt_image_launch_exits(self):
        c = container(self.gke)
        self.assertNotIn("command", c)
        self.assertNotIn("args", c)
        self.assert_exit(self.launch(entrypoint(RUNTIME / "trade-processor/Dockerfile"), jvm_env(c)))

    def test_demo_patch_exits_with_original_image_launcher(self):
        # Existing pinned image has the baseline entrypoint; env must be sufficient.
        c = container(self.demo)
        self.assertNotIn("command", c)
        self.assertNotIn("args", c)
        self.assert_exit(self.launch(["java", "-jar", "/opt/app/app.jar"], jvm_env(c)))

    def test_application_fault_still_can_be_caught(self):
        result = self.launch(entrypoint(RUNTIME / "trade-processor/Dockerfile"), mode="application-fault")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("EXIT_FLAG=true", result.stdout)
        self.assertIn("APPLICATION_FAULT_CAUGHT", result.stdout)
        self.assertIn("POST_CATCH_ALIVE", result.stdout)

    def test_source_generated_bytes_match(self):
        for rel in ("trade-processor/Dockerfile", "trade-processor/Dockerfile.compose", BASE_REL):
            with self.subTest(path=rel):
                self.assertEqual((OWNER / rel).read_bytes(), (RUNTIME / rel).read_bytes())
        rel = "generation/kubernetes/gke-demo/trade-processor.patch.json"
        self.assertEqual((PACK / rel).read_bytes(),
                         (RUNTIME / "YU18-risk-integration/spec-source" / rel).read_bytes())
        for name in ("HeapOomFixture.java", "test_projector_oom_exit.py"):
            self.assertEqual(Path(__file__).with_name(name).read_bytes(),
                (RUNTIME / "YU18-risk-integration/spec-source/tests/projector-oom-exit" / name).read_bytes())

    def test_full_runtime_changes_only_exit_option(self):
        inherited = ROOT / "specs/YU12-aeron-cluster/generation/runtime-overrides" / BASE_REL
        self.assertEqual((OWNER / BASE_REL).read_text().replace(" " + FLAG, ""), inherited.read_text())
        options = shlex.split(jvm_env(container(self.base)))
        self.assertEqual(options, ["-XX:MaxRAMPercentage=75", FLAG])

    def test_demo_patch_preserves_all_other_container_fields(self):
        before, after = container(self.gke), container(self.demo)
        expected = copy.deepcopy(before)
        p = self.patch["spec"]["template"]["spec"]["containers"][0]
        expected["image"] = p["image"]
        expected["env"] = [{"name": "JAVA_TOOL_OPTIONS", "value": FLAG}, *expected["env"]]
        self.assertEqual(after, expected)


class EvidenceResult(unittest.TextTestResult):
    def startTest(self, test):
        if not hasattr(self, "executed_names"):
            self.executed_names = []
        self.executed_names.append(test.id())
        super().startTest(test)


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ProjectorOomExitTest)
    result = unittest.TextTestRunner(verbosity=2, resultclass=EvidenceResult).run(suite)
    evidence = os.environ.get("RI24_EVIDENCE_DIR")
    if evidence:
        path = Path(evidence)
        path.mkdir(parents=True, exist_ok=True)
        (path / "commands.json").write_text(json.dumps(COMMANDS, indent=2) + "\n")
        xml = ET.Element("testsuite", name="ProjectorOomExitTest", tests=str(result.testsRun),
                         failures=str(len(result.failures)), errors=str(len(result.errors)),
                         skipped=str(len(result.skipped)))
        cases = {name: ET.SubElement(xml, "testcase", name=name)
                 for name in getattr(result, "executed_names", [])}
        for test, reason in result.failures + result.errors:
            name = getattr(test, "test_case", test).id()
            case = cases.get(name)
            if case is None:
                case = ET.SubElement(xml, "testcase", name=name)
            ET.SubElement(case, "failure").text = reason
        ET.ElementTree(xml).write(path / "tests.xml", encoding="unicode")
    raise SystemExit(0 if result.wasSuccessful() else 1)
