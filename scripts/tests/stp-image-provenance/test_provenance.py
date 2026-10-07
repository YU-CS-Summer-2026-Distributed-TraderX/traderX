#!/usr/bin/env python3
"""Actual builder/proof entrypoint controls with explicitly offline external tools."""
import difflib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
ME = 'finos/traderx/ordermatcher/lmax/MatchingEngine.java'
GW = 'finos/traderx/ordermatcher/cluster/ClusterGatewayMain.java'
LABEL = 'dev.traderx.stp.provenance'


class EntrypointControls(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='ri18-control-')
        self.root = Path(self.tmp.name)
        self.om = self.root / 'generated/code/target-generated/order-matcher'
        self.bin = self.root / 'bin'; self.bin.mkdir()
        self.store = self.root / 'fixture-store'; self.store.mkdir()
        (self.root / 'scripts/yu15').mkdir(parents=True)
        (self.root / 'scripts/proofs').mkdir(parents=True)
        for name in ('build-stp-boundary-images.sh', 'stp-image-provenance.py'):
            shutil.copy2(ROOT / 'scripts/yu15' / name, self.root / 'scripts/yu15' / name)
        shutil.copy2(ROOT / 'scripts/proofs/yu13-stp-and-replace.sh', self.root / 'scripts/proofs/proof.sh')
        for name in ('docker', 'kubectl', 'kind', 'java', 'git'):
            shutil.copy2(HERE / 'fake-command.py', self.bin / name); (self.bin / name).chmod(0o755)
        for rel, content in {
            'Dockerfile.cluster': 'FROM scratch\nARG JAR_FILE\nCOPY ${JAR_FILE} /app.jar\n',
            'build.gradle': '// fixture dependency declaration\n',
            'settings.gradle': "rootProject.name='fixture'\n",
            'gradle/wrapper/gradle-wrapper.properties': 'distributionUrl=fixture\n',
            'src/main/resources/input.txt': 'resource bytes\n',
            'src/main/java/' + ME: 'class MatchingEngine {\n    void cross() {\n        if (sameSelfMatchGroup(r.accountId, a.accountId)) {\n            preventSelfTrade();\n        }\n    }\n    void preventSelfTrade() {}\n}\n',
            'src/main/java/' + GW: 'class ClusterGatewayMain {\n    void route() {\n        server.createContext("/replace", this::handleReplace);\n    }\n}\n',
        }.items():
            path = self.om / rel; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(content)
        shutil.copy2(HERE / 'fake-command.py', self.om / 'gradlew'); (self.om / 'gradlew').chmod(0o755)
        patch = []
        for rel in ('src/main/java/' + ME, 'src/main/java/' + GW):
            before = (self.om / rel).read_text()
            after = before.replace('        if (sameSelfMatchGroup(r.accountId, a.accountId)) {\n            preventSelfTrade();\n        }\n', '').replace('    void preventSelfTrade() {}\n', '').replace('        server.createContext("/replace", this::handleReplace);\n', '')
            patch.extend(difflib.unified_diff(before.splitlines(True), after.splitlines(True), fromfile='a/' + rel, tofile='b/' + rel))
        for name in ('stp-boundary-revert.patch', 'stp-boundary-revert-yu18.patch'):
            (self.root / 'scripts/yu15' / name).write_text(''.join(patch))
        (self.root / 'pipeline').mkdir(); (self.root / 'pipeline/generator.sh').write_text('fixture composition\n')
        (self.root / 'specs').mkdir()
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'],
                        RI18_FIXTURE_STORE=str(self.store), GRADLE_USER_HOME=str(self.root / 'gradle-home'),
                        STP_PRE_TAG='ri18:pre', STP_FIX_TAG='ri18:fix', IMAGE_PRE='ri18:pre', IMAGE_FIX='ri18:fix')
        for key in ('JAVA_HOME', 'YU15_PLATFORM', 'DOCKER_NO_CACHE', 'GRADLE_OPTS', 'JAVA_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'DOCKER_DEFAULT_PLATFORM'):
            self.env.pop(key, None)
        self.build_pair()
        self.clear_trace()

    def tearDown(self):
        self.tmp.cleanup()

    def call(self, *args):
        return subprocess.run(args, env=self.env, cwd=self.root, capture_output=True, text=True, timeout=30)

    def build_pair(self, want=0):
        result = self.call('bash', str(self.root / 'scripts/yu15/build-stp-boundary-images.sh'))
        self.assertEqual(result.returncode, want, result.stdout + result.stderr)
        return result

    def clear_trace(self):
        (self.store / 'trace.jsonl').write_text('')

    def trace(self):
        return [json.loads(line) for line in (self.store / 'trace.jsonl').read_text().splitlines()]

    def proof(self, accepted, reason=''):
        result = self.call('bash', str(self.root / 'scripts/proofs/proof.sh'))
        self.assertEqual(result.returncode, 88 if accepted else 1, result.stdout + result.stderr)
        trace = self.trace()
        self.assertEqual(any(x[0] == 'kind' for x in trace), accepted, trace)
        if not accepted:
            self.assertIn(reason, result.stderr)
            self.assertFalse(any(x[0] == 'kubectl' for x in trace), trace)
        return result

    def images(self, mutate):
        path = self.store / 'images.json'; value = json.loads(path.read_text()); mutate(value); path.write_text(json.dumps(value))

    def test_unchanged_sources_with_new_mtimes(self):
        for path in self.om.rglob('*'):
            os.utime(path, (1900000000, 1900000000))
        self.proof(True)

    def test_changed_bytes_with_preserved_mtime(self):
        path = self.om / 'src/main/java' / ME
        old = path.stat(); path.write_text(path.read_text() + '// changed bytes\n'); os.utime(path, ns=(old.st_atime_ns, old.st_mtime_ns))
        self.proof(False, 'sourceSha256 input changed')

    def test_non_java_build_inputs(self):
        for rel in ('Dockerfile.cluster', 'build.gradle', 'settings.gradle', 'src/main/resources/input.txt', 'gradle/wrapper/gradle-wrapper.properties', 'gradlew'):
            with self.subTest(rel=rel):
                path = self.om / rel; data = path.read_bytes(); path.write_bytes(data + b'\n# changed input\n')
                self.clear_trace(); self.proof(False, 'sourceSha256 input changed'); path.write_bytes(data)

    def test_generation_composition_input(self):
        (self.root / 'pipeline/generator.sh').write_text('changed composition\n')
        self.proof(False, 'compositionSha256 input changed')

    def test_revert_patch_input(self):
        path = self.root / 'scripts/yu15/stp-boundary-revert-yu18.patch'; path.write_text(path.read_text() + '\n# changed patch\n')
        self.proof(False, 'patchSha256 input changed')

    def test_wrong_image_role(self):
        self.images(lambda v: v.__setitem__('ri18:pre', v['ri18:fix']))
        self.proof(False, 'wrong image role')

    def test_missing_provenance(self):
        self.images(lambda v: v['ri18:pre']['Config'].__setitem__('Labels', {}))
        self.proof(False, 'missing')

    def test_malformed_provenance(self):
        for value in ('{', '{}', 'null', '[1]'):
            with self.subTest(value=value):
                self.build_pair(); self.images(lambda v: v['ri18:pre']['Config']['Labels'].__setitem__(LABEL, value))
                self.clear_trace(); self.proof(False, 'STP image provenance')

    def test_transformed_role_mismatch(self):
        def mutate(value):
            p = json.loads(value['ri18:pre']['Config']['Labels'][LABEL]); p['roleSha256'] = '0' * 64
            value['ri18:pre']['Config']['Labels'][LABEL] = json.dumps(p)
        self.images(mutate); self.proof(False, 'transformed role content mismatch')

    def test_packaged_dependency_mismatch(self):
        self.images(lambda v: v['ri18:pre']['payload'].__setitem__('lib/fixture-dependency.jar', b'changed library'.hex()))
        self.proof(False, 'packaged classes/resources/dependencies mismatch')

    def test_broken_patch_refuses_builder(self):
        path = self.root / 'scripts/yu15/stp-boundary-revert-yu18.patch'; path.write_text('malformed patch\n')
        self.build_pair(want=1)
        self.assertFalse(any(x[:2] == ['docker', 'build'] for x in self.trace()))

    def test_builder_refuses_bad_provenance(self):
        for mutation in ('missing', 'malformed', 'wrong-role'):
            with self.subTest(mutation=mutation):
                self.env['RI18_INSPECT_MUTATION'] = mutation
                self.build_pair(want=1)
                self.env.pop('RI18_INSPECT_MUTATION')

    def test_deterministic_bounded_fingerprint(self):
        args = ['python3', str(self.root / 'scripts/yu15/stp-image-provenance.py'), 'fingerprint']
        before = self.call(*args); self.assertEqual(before.returncode, 0, before.stderr)
        for rel in ('build/noise', '.gradle/noise'):
            path = self.om / rel; path.parent.mkdir(parents=True, exist_ok=True); path.write_text('ignored output\n')
        (self.root / 'unrelated-notes.md').write_text('irrelevant prose\n')
        for path in self.om.rglob('*'): os.utime(path, (1900000000, 1900000000))
        after = self.call(*args); self.assertEqual(before.stdout, after.stdout)
        self.proof(True)

    def test_repeat_build_uses_cache_without_no_cache(self):
        before = json.loads((self.store / 'images.json').read_text())
        self.build_pair(); after = json.loads((self.store / 'images.json').read_text())
        self.assertEqual(before, after)
        builds = [x for x in self.trace() if x[:2] == ['docker', 'build']]
        self.assertEqual(len(builds), 2)
        self.assertTrue(all('--no-cache' not in x for x in builds))

    def test_executable_mode_input(self):
        path = self.om / 'gradlew'; path.chmod(0o644)
        self.proof(False, 'sourceSha256 input changed')

    def test_symlink_escape_refuses(self):
        (self.om / 'escape').symlink_to(self.root / 'scripts')
        self.proof(False, 'symlink is not a bounded build input')

    def test_missing_image_refuses(self):
        self.images(lambda v: v.pop('ri18:pre'))
        self.proof(False, 'STP image provenance')

    def test_ambient_build_overrides_refuse(self):
        for key in ('JAVA_TOOL_OPTIONS', 'JAVA_OPTS', 'ORG_GRADLE_PROJECT_fixture'):
            with self.subTest(key=key):
                self.env[key] = '-Dfixture=changed'
                self.clear_trace(); self.proof(False, 'unbounded build override')
                self.env.pop(key)

    def test_missing_selected_patch_refuses(self):
        (self.root / 'scripts/yu15/stp-boundary-revert-yu18.patch').unlink()
        self.proof(False, 'STP image provenance')


    def test_missing_generated_tree_refuses(self):
        shutil.rmtree(self.om)
        self.proof(False, 'missing generated build input')

    def test_host_build_properties_input(self):
        home = Path(self.env['GRADLE_USER_HOME']); home.mkdir()
        (home / 'gradle.properties').write_text('fixtureProperty=changed\n')
        self.proof(False, 'host Java toolchain changed')

    def test_resolved_base_pin_and_change(self):
        (self.om / 'Dockerfile.cluster').write_text('FROM fixture-base:21\nARG JAR_FILE\nCOPY ${JAR_FILE} /app.jar\n')
        self.images(lambda v: v.__setitem__('fixture-base:21',
            dict(Id='sha256:' + 'b' * 64, RepoDigests=['fixture-base@sha256:' + 'c' * 64], Config={'Labels': {}})))
        self.build_pair()
        dockerfiles = [json.loads(x) for x in (self.store / 'dockerfiles.jsonl').read_text().splitlines()]
        self.assertTrue(all('FROM fixture-base@sha256:' + 'c' * 64 in x for x in dockerfiles[-2:]))
        self.images(lambda v: v['fixture-base:21'].__setitem__('Id', 'sha256:' + 'd' * 64))
        self.clear_trace(); self.proof(False, 'resolved base input changed')


    def test_no_owned_containers_leak(self):
        self.proof(True)
        self.assertFalse(list(self.store.glob('ri18-provenance-*')))


if __name__ == '__main__':
    unittest.main(verbosity=2)
