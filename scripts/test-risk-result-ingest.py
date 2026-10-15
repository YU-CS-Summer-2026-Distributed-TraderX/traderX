#!/usr/bin/env python3
"""Local acceptance using operator-supplied saved Batch evidence. Makes no cloud calls."""
import argparse
import concurrent.futures
import json
import shutil
import sys
import tempfile
from pathlib import Path
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] /
                     'specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools'))
import result_ingest as r
from batch_prepare import read
from corpus import digest, encoded
p = argparse.ArgumentParser()
p.add_argument('--work', type=Path, required=True)
p.add_argument('--evidence', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=False)
checks = []
def refused(name, fn):
    try:
        fn()
    except (ValueError, OSError, KeyError):
        checks.append(name)
    else:
        raise AssertionError('accepted invalid input: ' + name)
first = r.ingest(a.work, a.evidence, a.output / 'store')
root = Path(first['directory'])
assert r.ingest(a.work, a.evidence, a.output / 'store')['status'] == 'ALREADY_IMPORTED'
checks.append('identical-replay-idempotent')
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    results = list(pool.map(lambda _: r.ingest(a.work, a.evidence, a.output / 'concurrent'), range(2)))
assert len({x['sha256'] for x in results}) == 1
checks.append('concurrent-identical-publication')
shutil.copytree(root, a.output / 'moved')
assert r.status(a.output / 'moved', first['sha256']) == first['result']
checks.append('moved-root-independent-of-original-path')
refused('wrong-trust-pin', lambda: r.status(root, '0' * 64))
(root / 'unexpected').write_text('extra')
refused('extra-file', lambda: r.status(root, first['sha256']))
(root / 'unexpected').unlink()
price = root / 'download/artifacts/job/result.json'
raw = price.read_bytes(); price.write_bytes(raw + b' ')
refused('tampered-artifact', lambda: r.status(root, first['sha256']))
price.write_bytes(raw)
(root / 'escape').symlink_to(a.work)
refused('symlink', lambda: r.status(root, first['sha256']))
(root / 'escape').unlink()
for label, relative, mutate in [
    ('wrong-batch-uid','download/job.json',lambda d:d.update(uid='wrong-uid')),
    ('wrong-control-generation','build/job-identity-object.json',lambda d:d.update(generation='1')),
    ('wrong-account','download/artifacts/connected-result.json',lambda d:d['mapping']['expected'].update(account_id='999')),
    ('wrong-cut','download/artifacts/connected-result.json',lambda d:d['mapping']['cut'].update(consensusSequence='99')),
    ('package-traversal','build/archive.json',lambda d:d.update(package_directory='../../elsewhere')),
]:
    dest = a.output / label; shutil.copytree(root, dest)
    path = dest / relative; obj=read(path);mutate(obj);path.write_bytes(encoded(obj))
    # Even replacing the outer inventory/pin must not bypass the underlying custody verifier.
    admission=read(dest/'admission.json');admission['files']=r.inventory(dest)
    (dest/'admission.json').write_bytes(encoded(admission))
    refused(label, lambda: r.status(dest, digest(dest/'admission.json')))
# A valid but different retained evidence set for the same run must not replace the import.
other=a.output/'different-download';shutil.copytree(a.evidence, other)
(other/'operator-note.txt').write_text('different custody')
refused('conflicting-replay', lambda:r.ingest(a.work, other, a.output/'store'))
assert r.status(root, first['sha256']) == first['result']
report={'checks':checks,'result':first,'cloud_calls':0}
(a.output/'report.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
