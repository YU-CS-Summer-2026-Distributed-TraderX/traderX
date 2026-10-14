#!/usr/bin/env python3
"""One frozen GCS object per Batch task; immutable attempt data, conditional commit last."""
import base64
import hashlib
import json
import os
import re
import time
import resource
from pathlib import Path
import shutil
import tempfile
import uuid

from google.api_core.exceptions import NotFound, PreconditionFailed
from google.cloud import storage
import google_crc32c
from preserve import convert, VERSION


def read_json(blob):
    return json.loads(blob.download_as_bytes())


def validate_commit(client, commit, source, manifest_sha, execution_image):
    if commit.get('execution_image') != execution_image:
        raise ValueError('completion execution identity mismatch')
    if commit.get('source') != source or commit.get('input_manifest_sha256') != manifest_sha or commit.get('format') != VERSION:
        raise ValueError('existing commit identity mismatch')
    if commit.get('status') != 'complete' or commit.get('rows', 0) <= 0 or not commit.get('parts'):
        raise ValueError('invalid/empty completion')
    if sum(p['rows'] for p in commit['parts']) != commit['rows']:
        raise ValueError('completion count mismatch')
    for p in commit['parts']:
        blob = client.bucket(p['bucket']).blob(p['object'], generation=int(p['generation']))
        blob.reload()
        if blob.size != p['bytes'] or blob.crc32c != p['crc32c'] or (blob.metadata or {}).get('sha256') != p['sha256']:
            raise ValueError('committed object metadata mismatch')


def run():
    started = time.monotonic()
    client = storage.Client(project=os.environ['GOOGLE_CLOUD_PROJECT'])
    uri = os.environ['INPUT_MANIFEST_URI']
    bucket, name = uri.removeprefix('gs://').split('/', 1)
    raw = client.bucket(bucket).blob(name, generation=int(os.environ['INPUT_MANIFEST_GENERATION'])).download_as_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != os.environ['INPUT_MANIFEST_SHA256']:
        raise ValueError('input manifest digest mismatch')
    manifest = json.loads(raw)
    if manifest.get('format') != 'ri30-input-v1' or not manifest.get('objects'):
        raise ValueError('invalid/empty input manifest')
    if len(manifest['objects']) != int(os.environ['INPUT_MANIFEST_OBJECT_COUNT']):
        raise ValueError('task plan/input object count mismatch')
    index = int(os.environ['BATCH_TASK_INDEX'])
    if index < 0 or index >= len(manifest['objects']):
        raise ValueError('task index outside frozen manifest')
    source = manifest['objects'][index]
    execution_image = os.environ['WORKER_IMAGE']
    if not re.fullmatch(r'[^\s]+@sha256:[a-f0-9]{64}', execution_image):
        raise ValueError('worker image identity must be pinned')
    run_id = hashlib.sha256((digest + execution_image + VERSION).encode()).hexdigest()
    destination = client.bucket(os.environ['DESTINATION_BUCKET'])
    root = f"ri30/v2/runs/{run_id}"
    commit_blob = destination.blob(f"{root}/commits/{source['source_id']}.json")
    try:
        existing = read_json(commit_blob)
    except NotFound:
        existing = None
    if existing is not None:
        validate_commit(client, existing, source, digest, execution_image)
        print(json.dumps({'status': 'already_complete', 'source_id': source['source_id']}))
        return
    attempt = uuid.uuid4().hex
    prefix = f"{root}/attempts/{source['source_id']}/{attempt}"
    scratch = os.environ.get('SCRATCH_DIR', '/scratch')
    Path(scratch).mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(scratch).free < source['size'] + 4 * 1024**3:
        raise ValueError('insufficient scratch for archive plus 4 GiB working reserve')
    with tempfile.TemporaryDirectory(dir=scratch, prefix='ri30-') as tmp:
        archive = Path(tmp) / ('input' + Path(source['name']).suffix)
        blob = client.bucket(source['bucket']).blob(source['name'], generation=source['generation'])
        blob.reload()
        if blob.size != source['size'] or blob.crc32c != source['crc32c']:
            raise ValueError('source metadata changed')
        blob.download_to_filename(str(archive), if_generation_match=source['generation'], checksum='crc32c')
        crc = google_crc32c.Checksum()
        with archive.open('rb') as f:
            for block in iter(lambda: f.read(8 * 1024 * 1024), b''):
                crc.update(block)
        if archive.stat().st_size != source['size'] or base64.b64encode(crc.digest()).decode() != source['crc32c']:
            raise ValueError('downloaded source checksum/size mismatch')

        progress = {"rows": 0, "parts": 0}
        def upload(path, info):
            target = destination.blob(f'{prefix}/{path.name}')
            target.metadata = {'sha256': info['sha256']}
            target.upload_from_filename(str(path), if_generation_match=0, checksum='crc32c')
            target.reload()
            progress['rows'] += info['rows']; progress['parts'] += 1
            if progress['parts'] % 20 == 0:
                print(json.dumps({'event': 'progress', **progress, 'elapsed_seconds': round(time.monotonic()-started, 1)}), flush=True)
            return {'bucket': destination.name, 'object': target.name,
                    'generation': int(target.generation), 'crc32c': target.crc32c}

        result = convert(archive, Path(tmp) / 'output', source_id=source['source_id'],
                         memory_limit=os.environ.get('DUCKDB_MEMORY_LIMIT', '4GB'), chunk_rows=500000, on_part=upload)
        result.update(elapsed_seconds=round(time.monotonic()-started, 2), peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss, source=source, input_manifest_sha256=digest, execution_image=execution_image, attempt=attempt)
        validate_commit(client, result, source, digest, execution_image)
        try:
            commit_blob.upload_from_string(json.dumps(result, sort_keys=True),
                                           content_type='application/json', if_generation_match=0)
        except PreconditionFailed:
            validate_commit(client, read_json(commit_blob), source, digest, execution_image)
        print(json.dumps({'status': 'complete', 'source_id': source['source_id'], 'rows': result['rows']}))


if __name__ == '__main__':
    run()
