#!/usr/bin/env python3
"""Latency L6a (FR-GMR-14): put the member StatefulSet on the C media driver, for an A/B. Not a production render.

    kubectl get sts order-matcher-cluster -o json | python3 aeronmd-sidecar.py --image IMG [--cpu 3] | kubectl replace -f -

Adds the `aeronmd` native sidecar (an initContainer with restartPolicy Always: up before the member, stopped after it,
so the member's clean close still has its driver) on the member's own `shm` volume, and sets CLUSTER_MEDIA_DRIVER=external
on the member. The C driver mirrors what the member's Java driver would have run, read from the same StatefulSet:
  AERON_DIR = CLUSTER_AERON_DIR, deleted on start (the driver owns the dir, as dirDeleteOnStart did), sparse term buffers;
  AERON_THREADING_MODE = CLUSTER_MEDIA_DRIVER_THREADING (default DEDICATED);
  sender/receiver/conductor idle = CLUSTER_IDLE_STRATEGY_<AGENT>, else CLUSTER_IDLE_STRATEGY, else CLUSTER_IDLE_SLEEP_MS,
    else Aeron's default, each translated to the C name (lowpark -> backoff 10-5-1000-1000, busyspin -> noop);
  every -Daeron.<x> driver/socket property of the member's JAVA_TOOL_OPTIONS -> AERON_<X> (so_rcvbuf, cycle thresholds).
--set NAME=VALUE overrides or adds any AERON_* entry (e.g. a different idle for the C arm).
Guaranteed QoS: the sidecar takes --cpu whole CPUs (requests == limits) FROM the member, so the pod's CPU is unchanged and
fits the same node; its memory is the shm sizeLimit + 256Mi, because tmpfs pages are charged to the process that writes
them and the driver's receiver writes every inbound image. Revert by replacing with the JSON saved before the patch.
"""
import argparse
import json
import re
import sys

STS = 'order-matcher-cluster'
# Java knob -> (C strategy, C init args); None = leave the C default.
IDLE = {'lowpark': ('backoff', '10-5-1000-1000'), 'backoff': ('backoff', None), 'busyspin': ('noop', None),
        'noop': ('noop', None), 'yielding': ('yield', None), 'yield': ('yield', None)}
DRIVER_PROPS = ('aeron.socket.', 'aeron.driver.', 'aeron.mtu.', 'aeron.rcv.', 'aeron.term.', 'aeron.ipc.',
                'aeron.publication.', 'aeron.client.liveness.', 'aeron.timer.')


def mib(q):
    units = {'Ki': 1 / 1024, 'Mi': 1, 'Gi': 1024, 'Ti': 1024 * 1024}
    m = re.fullmatch(r'(\d+)(Ki|Mi|Gi|Ti)', q)
    if not m:
        raise SystemExit(f'unsupported quantity {q!r}')
    return int(int(m[1]) * units[m[2]])


def driver_env(env):
    def get(n):
        v = env.get(n)
        return v.strip() if v and v.strip() else None
    out = {'AERON_DIR': get('CLUSTER_AERON_DIR') or '/dev/shm/aeron-cluster', 'AERON_DIR_DELETE_ON_START': 'true',
           'AERON_TERM_BUFFER_SPARSE_FILE': 'true', 'AERON_PRINT_CONFIGURATION': 'true',
           'AERON_THREADING_MODE': (get('CLUSTER_MEDIA_DRIVER_THREADING') or 'DEDICATED').upper()}
    sleep = get('CLUSTER_IDLE_SLEEP_MS')
    for agent in ('SENDER', 'RECEIVER', 'CONDUCTOR'):
        java = (get('CLUSTER_IDLE_STRATEGY_' + agent) or get('CLUSTER_IDLE_STRATEGY') or '').lower()
        if java:
            if java not in IDLE:
                raise SystemExit(f'no C equivalent for idle {java!r}')
            name, args = IDLE[java]
        elif sleep and int(sleep) > 0:
            name, args = 'sleep-ns', str(int(sleep) * 1_000_000)
        else:
            continue
        out[f'AERON_{agent}_IDLE_STRATEGY'] = name
        if args:
            out[f'AERON_{agent}_IDLE_STRATEGY_INIT_ARGS'] = args
    for tok in (env.get('JAVA_TOOL_OPTIONS') or '').split():
        m = re.fullmatch(r'-D(aeron\.[^=]+)=(.*)', tok)
        if m and m[1].startswith(DRIVER_PROPS):
            out[m[1].upper().replace('.', '_')] = m[2]
    return out


def patch(sts, image, cpu, overrides):
    pod = sts['spec']['template']['spec']
    member = next(c for c in pod['containers'] if any(e['name'] == 'CLUSTER_AERON_DIR' for e in c.get('env', [])))
    env = {e['name']: e.get('value') for e in member['env']}
    shm = next(v for v in pod['volumes'] if v.get('emptyDir', {}).get('medium') == 'Memory')
    mount = next(m for m in member['volumeMounts'] if m['name'] == shm['name'])
    if not driver_env(env)['AERON_DIR'].startswith(mount['mountPath'].rstrip('/') + '/'):
        raise SystemExit(f'CLUSTER_AERON_DIR is not on the Memory volume mounted at {mount["mountPath"]}')
    res = member['resources']
    have = int(res['limits']['cpu'])
    if str(res['requests']['cpu']) != str(res['limits']['cpu']) or have <= cpu:
        raise SystemExit(f'member cpu {res} is not a Guaranteed whole number above {cpu}')
    res['requests']['cpu'] = res['limits']['cpu'] = str(have - cpu)
    member['env'] = [e for e in member['env'] if e['name'] != 'CLUSTER_MEDIA_DRIVER'] + [
        {'name': 'CLUSTER_MEDIA_DRIVER', 'value': 'external'}]
    denv = {**driver_env(env), **overrides}
    memory = f"{mib(shm['emptyDir'].get('sizeLimit', '1Gi')) + 256}Mi"
    side = {'name': 'aeronmd', 'image': image, 'restartPolicy': 'Always',
            'env': [{'name': k, 'value': v} for k, v in sorted(denv.items())],
            'resources': {'requests': {'cpu': str(cpu), 'memory': memory}, 'limits': {'cpu': str(cpu), 'memory': memory}},
            'volumeMounts': [{'name': shm['name'], 'mountPath': mount['mountPath']}]}
    pod['initContainers'] = [c for c in pod.get('initContainers', []) if c['name'] != 'aeronmd'] + [side]
    sts['spec']['template'].setdefault('metadata', {}).setdefault('annotations', {})[
        'kubectl.kubernetes.io/default-container'] = member['name']
    return sts


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--image', required=True)
    p.add_argument('--cpu', type=int, default=3, help='whole CPUs for the driver, taken from the member (DEDICATED: 3)')
    p.add_argument('--set', action='append', default=[], metavar='AERON_X=V')
    a = p.parse_args()
    overrides = dict(s.split('=', 1) for s in a.set)
    doc = json.load(sys.stdin)
    items = doc['items'] if doc.get('kind') == 'List' else [doc]
    for d in items:
        if d.get('kind') == 'StatefulSet' and d['metadata']['name'] == STS:
            patch(d, a.image, a.cpu, overrides)
            break
    else:
        raise SystemExit(f'no StatefulSet {STS} in the input')
    json.dump(doc, sys.stdout, indent=2)


if __name__ == '__main__':
    main()
