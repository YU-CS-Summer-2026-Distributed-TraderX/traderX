#!/usr/bin/env python3
"""Exercise real Git CRLF checkout filters; no Windows host or external network required."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = Path('specs/YU18-eod-risk-bundles/generation/runtime-overrides/eod-risk-bundles/tests/fixtures')


def run(*args, cwd=None):
    return subprocess.run(args,cwd=cwd,check=True,capture_output=True,text=True,
                          env={**os.environ,'GIT_CONFIG_NOSYSTEM':'1','GIT_CONFIG_GLOBAL':os.devnull})


def main():
    with tempfile.TemporaryDirectory(prefix='yu18-checkout-') as scratch:
        root=Path(scratch);source=root/'source';source.mkdir()
        run('git','init','-q',str(source))
        run('git','config','core.autocrlf','false',cwd=source)
        shutil.copytree(ROOT/PREFIX/'golden-v1',source/PREFIX/'golden-v1')
        cut=PREFIX/'shared/note/cut.txt';(source/cut).parent.mkdir(parents=True)
        (source/cut).write_bytes((ROOT/cut).read_bytes())
        (source/'unrelated.csv').write_bytes(b'name,value\nexample,1\n')
        def commit(message):
            run('git','add','.',cwd=source)
            run('git','-c','user.name=Fixture Test','-c','user.email=fixture@example.invalid',
                '-c','commit.gpgsign=false','-c','core.hooksPath='+os.devnull,'commit','-qm',message,cwd=source)
        commit('without attributes')
        old=root/'old';run('git','clone','-q','-c','core.autocrlf=true',str(source),str(old))
        manifest=PREFIX/'golden-v1/basic/manifest.json'
        if b'\r\n' not in (old/manifest).read_bytes(): raise AssertionError('negative control did not translate bytes')
        verifier=ROOT/PREFIX.parent.parent/'verify_golden.py'
        failed=subprocess.run([sys.executable,str(verifier),str(old/PREFIX/'golden-v1')],capture_output=True,text=True)
        if failed.returncode==0 or 'CRLF line endings' not in failed.stderr: raise AssertionError(failed.stderr)
        shutil.copyfile(ROOT/'.gitattributes',source/'.gitattributes');commit('scoped byte-exact attributes')
        new=root/'new';run('git','clone','-q','-c','core.autocrlf=true',str(source),str(new))
        for p in (source/PREFIX).rglob('*'):
            if p.is_file() and p.read_bytes()!=(new/p.relative_to(source)).read_bytes():
                raise AssertionError('checkout changed '+str(p))
        if b'\r\n' not in (new/'unrelated.csv').read_bytes(): raise AssertionError('attributes unexpectedly affected unrelated files')
        run(sys.executable,str(verifier),str(new/PREFIX/'golden-v1'))
        print('PASS: real Git core.autocrlf=true checkout preserves fixture bytes; unprotected control fails with CRLF diagnostic; unrelated CSV still translates.')


if __name__=='__main__':main()
