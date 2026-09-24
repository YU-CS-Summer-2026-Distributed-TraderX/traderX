"""Explicit OFFLINE storage provisioning. Never used by normal process startup.

Legacy evidence must be operator-selected archived configuration, plus hashes of
retained storage files. The tool verifies those bytes; it cannot establish who
created that configuration or whether an external historical assertion is true.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile

FIELDS = {'schema', 'epoch', 'eventIdScheme', 'storageLineage', 'projectionScope', 'adoptionEvidenceSha256'}
FILE = 'run-identity.json'
EVIDENCE = 'run-adoption-evidence.json'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON field: ' + key)
        result[key] = value
    return result


def parse(raw):
    return json.loads(raw, object_pairs_hook=unique)


def descriptor(raw):
    if len(raw) > 16384:
        raise ValueError('descriptor exceeds 16384 bytes')
    d = parse(raw)
    if not isinstance(d, dict) or set(d) != FIELDS or d['schema'] != 'traderx.run.v1':
        raise ValueError('RUN_DESCRIPTOR_SCHEMA')
    for key in ('storageLineage', 'projectionScope'):
        if not isinstance(d[key], str) or not re.fullmatch(r'[a-z0-9_-]{1,64}', d[key]):
            raise ValueError('RUN_DESCRIPTOR_IDENTITY')
    if not isinstance(d['epoch'], str) or not d['epoch'].strip():
        raise ValueError('RUN_DESCRIPTOR_EPOCH')
    if d['eventIdScheme'] == 'epoch-v1':
        if not re.fullmatch(r'[a-z0-9_]{1,25}', d['epoch']) or d['projectionScope'] == 'legacy-unknown' or d['adoptionEvidenceSha256'] is not None:
            raise ValueError('RUN_V1_DESCRIPTOR_INVALID')
    elif d['eventIdScheme'] == 'legacy-v0':
        if d['projectionScope'] != 'legacy-unknown' or not isinstance(d['adoptionEvidenceSha256'], str) or not re.fullmatch(r'[0-9a-f]{64}', d['adoptionEvidenceSha256']):
            raise ValueError('RUN_LEGACY_ADOPTION_REQUIRES_EVIDENCE')
    else:
        raise ValueError('RUN_ID_SCHEME_UNKNOWN')
    return d


def read_regular(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError('not a regular non-symlink file: ' + str(path))
    return path.read_bytes()


def sync_dir(directory):
    fd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def install(directory, name, raw):
    """Atomic no-replace link, including safe retry after link-before-dir-fsync."""
    target = directory / name
    if target.exists() or target.is_symlink():
        if read_regular(target) != raw:
            raise ValueError('RUN_IMMUTABLE_FILE_CONFLICT: ' + name)
        sync_dir(directory)
        return
    fd, temporary = tempfile.mkstemp(prefix='.run-provision-', dir=directory)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(raw)
            output.flush()
            os.fsync(output.fileno())
        try:
            os.link(temporary, target)
        except FileExistsError:
            if read_regular(target) != raw:
                raise ValueError('RUN_IMMUTABLE_FILE_CONFLICT: ' + name)
        sync_dir(directory)
    finally:
        os.unlink(temporary)
        sync_dir(directory)


def validate_evidence(directory, raw, d, storage_member=None):
    if sha(raw) != d['adoptionEvidenceSha256']:
        raise ValueError('RUN_EVIDENCE_HASH_MISMATCH')
    e = parse(raw)
    if set(e) not in ({'schema', 'epoch', 'sourceConfiguration', 'storageFiles'}, {'schema', 'epoch', 'sourceConfiguration', 'storageMembers'}) or e['schema'] != 'traderx.legacy-evidence.v1' or e['epoch'] != d['epoch']:
        raise ValueError('RUN_EVIDENCE_SCHEMA')
    # Archived source configuration is deliberately supplied; never inspect current environment.
    c = e['sourceConfiguration']
    config_raw = read_regular(c['path'])
    if sha(config_raw) != c['sha256']:
        raise ValueError('RUN_SOURCE_CONFIGURATION_HASH_MISMATCH')
    config = parse(config_raw)
    if config.get('tradePublisherEpoch') != d['epoch'] or config.get('orderPublisherEpoch') != d['epoch'] or config.get('eventIdScheme') != 'legacy-v0':
        raise ValueError('RUN_LEGACY_SOURCE_AMBIGUOUS: publisher epochs/scheme do not agree')
    if 'storageMembers' in e:
        members = e['storageMembers']
        if not isinstance(members, dict) or not members or storage_member not in members:
            raise ValueError('RUN_STORAGE_MEMBER_EVIDENCE_REQUIRED')
        if any(not isinstance(k, str) or not re.fullmatch(r'[a-z0-9_-]{1,64}', k) or not isinstance(v, dict) or not v for k, v in members.items()):
            raise ValueError('RUN_STORAGE_MEMBER_EVIDENCE_INVALID')
        expected = members[storage_member]
    else:
        if storage_member is not None:
            raise ValueError('RUN_STORAGE_MEMBER_EVIDENCE_REQUIRED')
        expected = e['storageFiles']
    if not isinstance(expected, dict) or not expected:
        raise ValueError('RUN_EVIDENCE_EMPTY_STORAGE')
    actual = {}
    for p in directory.rglob('*'):
        if p.name in {FILE, EVIDENCE, '.run-provision.lock'} or p.name.startswith('.run-provision-'):
            continue
        if p.is_symlink():
            raise ValueError('RUN_STORAGE_SYMLINK')
        if p.is_file():
            actual[p.relative_to(directory).as_posix()] = sha(read_regular(p))
    if actual != expected:
        raise ValueError('RUN_STORAGE_EVIDENCE_MISMATCH')


def provision(directory, raw, *, offline=False, evidence=None, storage_member=None):
    if not offline:
        raise ValueError('explicit offline maintenance acknowledgement required')
    d = descriptor(raw)
    directory = Path(directory)
    if directory.is_symlink():
        raise ValueError('RUN_STORAGE_SYMLINK')
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / '.run-provision.lock').open('a+b') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        target = directory / FILE
        if target.exists() or target.is_symlink():
            if read_regular(target) != raw:
                raise ValueError('RUN_STORAGE_DESCRIPTOR_MISMATCH')
            if d['eventIdScheme'] == 'legacy-v0' and sha(read_regular(directory / EVIDENCE)) != d['adoptionEvidenceSha256']:
                raise ValueError('RUN_EVIDENCE_HASH_MISMATCH')
            sync_dir(directory)
            return sha(raw)
        entries = [p for p in directory.iterdir() if p.name != '.run-provision.lock' and not p.name.startswith('.run-provision-')]
        if d['eventIdScheme'] == 'epoch-v1':
            if entries:
                raise ValueError('RUN_FRESH_STORAGE_NOT_EMPTY')
        else:
            if evidence is None:
                raise ValueError('RUN_LEGACY_ADOPTION_REQUIRES_EVIDENCE')
            validate_evidence(directory, evidence, d, storage_member)
            install(directory, EVIDENCE, evidence)
        install(directory, FILE, raw)
        return sha(raw)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--storage', required=True)
    p.add_argument('--descriptor', required=True)
    p.add_argument('--legacy-evidence')
    p.add_argument('--storage-member', help='member key in a shared legacy storageMembers evidence manifest')
    p.add_argument('--offline', action='store_true', help='confirm this storage has no running writers')
    a = p.parse_args()
    result = provision(a.storage, read_regular(a.descriptor), offline=a.offline,
                       evidence=read_regular(a.legacy_evidence) if a.legacy_evidence else None, storage_member=a.storage_member)
    print(json.dumps({'descriptorHash': result, 'provisioned': True}))


if __name__ == '__main__':
    main()
