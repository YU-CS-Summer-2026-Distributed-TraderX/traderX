#!/usr/bin/env python3
"""Exercise real guard/launcher code with owned files and inert external-command fixtures."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class PostTradeConfigTest(unittest.TestCase):
    GOOD = ('settlement.t-plus-days=2\norder-matcher.base-url=${ORDER_MATCHER_BASE_URL:http://matcher}\n'
            'auth.jwt.secret=${AUTH_JWT_SECRET:synthetic-key}\n'
            'auth.dev-token.master-secret=${AUTH_DEV_TOKEN_MASTER_SECRET:synthetic-master}\n')

    def run_guard(self, text):
        with tempfile.TemporaryDirectory(prefix='ri31-properties-') as tmp:
            path = Path(tmp) / 'application.properties'
            path.write_text(text)
            return subprocess.run(['python3', str(ROOT / 'scripts/lib/check-post-trade-auth.py'), str(path)],
                                  capture_output=True, text=True)

    def test_current_jwt_binding_passes(self):
        self.assertEqual(0, self.run_guard(self.GOOD).returncode)

    def test_legacy_token_cannot_substitute_for_jwt(self):
        text = self.GOOD.replace('auth.jwt.secret=${AUTH_JWT_SECRET:synthetic-key}', 'recon.control.token=old')
        self.assertNotEqual(0, self.run_guard(text).returncode)

    def test_comments_literals_wrong_binding_and_shadowing_refuse(self):
        for text in [self.GOOD.replace('auth.jwt.secret=', '#auth.jwt.secret='),
                     self.GOOD.replace('${AUTH_JWT_SECRET:synthetic-key}', 'literal-key'),
                     self.GOOD.replace('AUTH_JWT_SECRET:', 'WRONG_ENV:'),
                     self.GOOD + 'auth.jwt.secret=${AUTH_JWT_SECRET:other}\n']:
            with self.subTest(text=text):
                self.assertNotEqual(0, self.run_guard(text).returncode)


class LauncherBuildTest(unittest.TestCase):
    def run_launcher(self, mode='good', extra=()):
        with tempfile.TemporaryDirectory(prefix='ri31-launcher-') as tmp:
            root = Path(tmp)
            wrapper = Path(os.environ.get('RI31_TEST_WRAPPER', ROOT / 'scripts/start-state-010-kubernetes-runtime-generated.sh'))
            generated = root / 'generated/code/target-generated'
            # Use the real installed layout so its injected default root is exercised too.
            installed = 'export TRADERX_LOCAL_RUNTIME_SCRIPT=1' in wrapper.read_text()
            scripts = (generated if installed else root) / 'scripts'
            lib = scripts / 'lib'
            lib.mkdir(parents=True)
            shutil.copy(wrapper, scripts / wrapper.name)
            for name in ['kubernetes-smoke-readiness.sh', 'build-jvm-jar.sh']:
                shutil.copy(ROOT / 'scripts/lib' / name, lib / name)
            generated = root / 'generated/code/target-generated'
            runtime = generated / 'kubernetes-runtime'
            (runtime / 'kind').mkdir(parents=True)
            (runtime / 'manifests/base').mkdir(parents=True)
            (runtime / 'kind/cluster-config.yaml').write_text('kind: Cluster\n')
            (runtime / 'build-plan.json').write_text(json.dumps({
                'kindClusterName': 'ri31-fixture', 'namespace': 'traderx', 'hostPort': 8080,
                'edgeService': 'edge', 'deployments': [], 'images': [
                    {'name': 'fixture', 'image': 'fixture:test', 'context': 'fixture', 'dockerfile': 'Dockerfile'}]}))
            context = generated / 'fixture'
            (context / 'build/libs').mkdir(parents=True)
            (context / 'Dockerfile').write_text('FROM scratch\n')
            (context / 'build/libs/stale.jar').touch()
            if mode != 'node':
                (context / 'build.gradle').touch()
            if mode != 'missing':
                gradle = context / 'gradlew'
                gradle.write_text('#!/bin/bash\nset -eu\nprintf "GRADLE %s\\n" "$*" >> "$TRACE"\n'
                                  + ('exit 42\n' if mode == 'failing' else
                                     'rm -rf build/libs\nmkdir -p build/libs\ntouch build/libs/fresh.jar\n'))
                gradle.chmod(0o700)
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            stubs = {
                'kind': '#!/bin/bash\necho ri31-fixture\n',
                'kubectl': '#!/bin/bash\n[[ "$1" != apply ]] || exit 88\nexit 0\n',
                'docker': '#!/bin/bash\nif [[ "$1" == ps ]]; then echo ri31-fixture-control-plane; fi\n'
                          'if [[ "$1" == build ]]; then echo DOCKER >> "$TRACE"; fi\nexit 0\n',
            }
            for name, text in stubs.items():
                p = bin_dir / name
                p.write_text(text)
                p.chmod(0o700)
            trace = root / 'trace'
            env = {**os.environ, 'PATH': str(bin_dir) + ':' + os.environ['PATH'],
                   'TRADERX_LOCAL_RUNTIME_SCRIPT': '1', 'TRADERX_SKIP_GENERATE': '1',
                   'TRADERX_USE_PUBLISHED_IMAGES': '0', 'TRACE': str(trace)}
            env.pop('TRADERX_GENERATED_ROOT', None)
            result = subprocess.run(['bash', str(scripts / wrapper.name), '--skip-generate', *extra],
                                    env=env, capture_output=True, text=True)
            return result, trace.read_text() if trace.exists() else '', {
                'fresh': (context / 'build/libs/fresh.jar').exists(),
                'stale': (context / 'build/libs/stale.jar').exists()}

    def test_fresh_jar_precedes_actual_docker_build(self):
        result, trace, files = self.run_launcher()
        self.assertEqual(88, result.returncode, result.stdout + result.stderr)  # inert apply fence after image preparation
        self.assertEqual(['GRADLE --no-daemon clean bootJar', 'DOCKER'], trace.splitlines())
        self.assertTrue(files['fresh'])
        self.assertFalse(files['stale'])

    def test_failed_or_missing_gradle_cannot_reach_docker(self):
        for mode in ['missing', 'failing']:
            with self.subTest(mode=mode):
                result, trace, _ = self.run_launcher(mode)
                self.assertNotEqual(88, result.returncode)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn('DOCKER', trace)
                if mode == 'failing':
                    self.assertEqual(['GRADLE --no-daemon clean bootJar'], trace.splitlines())
                else:
                    self.assertIn('has build.gradle but no executable gradlew:', result.stdout + result.stderr)

    def test_non_jvm_and_explicit_reuse_do_not_run_gradle(self):
        for mode, extra in [('node', ()), ('good', ('--skip-build',))]:
            result, trace, _ = self.run_launcher(mode, extra)
            self.assertEqual(88, result.returncode, result.stdout + result.stderr)
            self.assertNotIn('GRADLE', trace)

    def test_dry_run_describes_preparation_without_executing_commands(self):
        result, trace, _ = self.run_launcher(extra=('--dry-run',))
        self.assertEqual(0, result.returncode)
        self.assertIn('build-jvm-jar.sh', result.stdout)
        self.assertEqual('', trace)


if __name__ == '__main__':
    unittest.main(verbosity=2)
