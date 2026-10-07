#!/usr/bin/env python3
"""Validate member-0 storage evidence; no Kubernetes or clock access."""
import datetime as dt
import json
import re
import sys


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def object_at(raw, kind, name, namespace=None):
    obj = json.loads(raw)
    require(isinstance(obj, dict) and obj.get('kind') == kind, f'invalid {kind} object')
    meta = obj.get('metadata', {})
    require(meta.get('name') == name and isinstance(meta.get('uid'), str)
            and bool(re.fullmatch(r'[a-zA-Z0-9-]+', meta['uid'])), f'{kind} identity unavailable')
    if namespace is not None:
        require(meta.get('namespace') == namespace, f'{kind} namespace mismatch')
    require(not meta.get('deletionTimestamp'), f'{kind} is terminating')
    return obj


def member_claim(raw, namespace):
    pod = object_at(raw, 'Pod', 'order-matcher-cluster-0', namespace)
    owners = pod['metadata'].get('ownerReferences', [])
    require(sum(o.get('kind') == 'StatefulSet' and o.get('name') == 'order-matcher-cluster'
                and o.get('controller') is True and bool(o.get('uid')) for o in owners) == 1,
            'member is not controlled by order-matcher-cluster')
    containers = [c for c in pod['spec']['containers'] if c.get('name') == 'cluster-node']
    require(len(containers) == 1, 'cluster-node container unavailable or ambiguous')
    container = containers[0]
    base = [e for e in container.get('env', []) if e.get('name') == 'CLUSTER_BASE_DIR']
    require(len(base) == 1 and base[0].get('value') == '/data' and 'valueFrom' not in base[0],
            'cluster storage directory is not explicitly /data')
    mounts = [m for m in container.get('volumeMounts', [])
              if m.get('mountPath') == '/data' or m.get('mountPath', '').startswith('/data/')]
    require(len(mounts) == 1 and mounts[0].get('mountPath') == '/data', '/data mount unavailable or shadowed')
    mount = mounts[0]
    require(not mount.get('readOnly') and not mount.get('subPath') and not mount.get('subPathExpr'),
            '/data mount is read-only or uses a subpath')
    volumes = [v for v in pod['spec'].get('volumes', []) if v.get('name') == mount.get('name')]
    require(len(volumes) == 1, '/data volume unavailable or ambiguous')
    volume = volumes[0]
    require(set(volume) == {'name', 'persistentVolumeClaim'}, '/data is not exclusively PVC backed')
    backing = volume['persistentVolumeClaim']
    require(backing.get('claimName') == 'data-order-matcher-cluster-0' and not backing.get('readOnly'),
            '/data does not use the documented writable member-0 claim')
    return backing['claimName']


def anchor(pod_raw, pvc_raw, pv_raw, namespace):
    claim = member_claim(pod_raw, namespace)
    pvc = object_at(pvc_raw, 'PersistentVolumeClaim', claim, namespace)
    require(pvc.get('status', {}).get('phase') == 'Bound', 'member claim is not Bound')
    volume = pvc.get('spec', {}).get('volumeName')
    require(isinstance(volume, str) and bool(re.fullmatch(r'[a-z0-9][a-z0-9.-]*', volume)),
            'bound volume name unavailable or invalid')
    pv = object_at(pv_raw, 'PersistentVolume', volume)
    require(pv.get('status', {}).get('phase') == 'Bound', 'member volume is not Bound')
    ref = pv.get('spec', {}).get('claimRef', {})
    require(ref.get('name') == claim and ref.get('namespace') == namespace
            and ref.get('uid') == pvc['metadata']['uid'], 'volume claimRef does not match the member claim')
    ts = pvc['metadata'].get('creationTimestamp', '')
    require(isinstance(ts, str) and bool(re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?Z', ts)),
            'claim creationTimestamp is not a supported UTC timestamp')
    parsed = dt.datetime.fromisoformat(ts[:-1] + '+00:00')
    elapsed = parsed - dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc)
    ms = elapsed // dt.timedelta(milliseconds=1)
    require(ms > 0, 'claim creationTimestamp must follow the Unix epoch')
    return f'{ms}\t{ts}\t{pvc["metadata"]["uid"]}\t{volume}'


def main():
    mode, namespace = sys.argv[1:3]
    if mode == 'claim':
        print(member_claim(sys.stdin.read(), namespace))
    elif mode == 'volume':
        pvc = object_at(sys.stdin.read(), 'PersistentVolumeClaim', 'data-order-matcher-cluster-0', namespace)
        require(pvc.get('status', {}).get('phase') == 'Bound', 'member claim is not Bound')
        volume = pvc.get('spec', {}).get('volumeName', '')
        require(isinstance(volume, str) and bool(re.fullmatch(r'[a-z0-9][a-z0-9.-]*', volume)), 'invalid volumeName')
        print(volume)
    elif mode == 'anchor':
        values = json.load(sys.stdin)
        print(anchor(*values, namespace))
    elif mode == 'deployment':
        raw = sys.stdin.read()
        if not raw.strip():
            print('absent')
        else:
            object_at(raw, 'Deployment', 'price-publisher', namespace)
            print('present')
    else:
        raise ValueError('unknown evidence operation')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, TypeError, IndexError, AttributeError) as exc:
        print(f'[epoch] unavailable: {exc}', file=sys.stderr)
        sys.exit(1)
