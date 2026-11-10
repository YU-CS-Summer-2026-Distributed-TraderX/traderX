"""kind-yu18-rig.py — render a disposable kind rig for the yu18-* proofs, in production's read-model shape.

The YU17 kind cluster tier (members, gateway, NATS JetStream, MariaDB, trade-processor) with what production
adds on top, taken from prepare.py's own functions rather than restated: the JetStream projection and fill feed
(configure_private_runtime, minus the TLS socket), and ADR-097's snapshot read model on per-partition streams
(snapshot_read_model's environment, minus its GKE pools and disks). Plus what the FIX proofs need: CLIENT1 bound to
auth-secrets/fix-session-password, ADR-086 sessions in JetStream, status from the order read model.

    python3 scripts/proofs/kind-yu18-rig.py --tag gap18 [--partitions 4] [--gateways 1] | kubectl --context kind-<c> apply -f -

--history adds the history store as production renders it (prepare.py's history_store, FR-THT-02): ClickHouse and
history-feed (traderx/history-feed:<tag>) on trade-processor's streams, minus the GKE pool, storage class and sizes
(kind's default class; requests a laptop's Docker holds). --history-hot-days N also renders FR-THT-03's tiered policy,
its cold disk on an S3Mock in the namespace instead of GCS (prepare.py points it at the bucket's XML API). Since the
ADR-102 cutover (2026-11-08) the blotter reads the store, so --history also renders position-service
(traderx/position-service:<tag>). --eod adds the rest of the EOD chain: price-publisher (its marks) and risk-extract
(the engine's cut, traderx/cluster-node:<tag>), credentials from the render's own Secrets. --binary adds the gateway's
binary port (prepare.add_binary_port) for binary-load.mjs, the production load driver (FR-THT-10's drain proof).
--tp-replicas N renders trade-processor as production's shard_read_model does (FR-DRS-45): one Recreate Deployment, or
above 1 a StatefulSet whose pods each own a block of the --partitions (scripts/proofs/kind-tp-ownership.sh).
--throughput sets the members as the throughput run renders them (prepare.throughput_members: egress coalescing,
packed snapshots) and the gateway at production's 4 guaranteed CPUs: without coalescing one pipelined gateway session's
acks overrun the members' best-effort egress, and at kind's 1-CPU limit the gateway is CFS-throttled (2026-11-10: 470 of
2,803 periods, a queue p99 of 22.7 ms).

Images: traderx/cluster-node:<tag> (build-cluster-image.sh) and traderx/trade-processor:<tag>
(build-service-images.sh), loaded into the kind nodes. Secrets are random per render: nothing to leak, and
kind's dev literals are refused by FR-ERH-S5 anyway (ALLOW_DEV_SECRETS stays true for the one-off proof pods only
through the manifests' own value).
"""
import argparse, importlib.util, json, pathlib, secrets, sys, yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
K = ROOT / 'specs/YU17-otc-rates/generation/kubernetes/cluster'
spec = importlib.util.spec_from_file_location('prepare', ROOT / 'scripts/gcp-compsci/prepare.py')
prep = importlib.util.module_from_spec(spec); spec.loader.exec_module(prep)


def history(docs, tag, hot_days, registry='', pool='', cold_bucket='', ns='traderx'):
    """FR-THT-02's render on kind: prepare.history_store itself, then only what kind cannot take swapped out.
    With a registry (GKE, a disposable namespace) production's shape is kept instead: its sizes and Hyperdisk class
    (under a tht- name, reclaim Delete, so the proof leaves no disk), the locked ClickHouse digest, its own tainted
    pool, the cold disk on gs://<cold_bucket>/<ns>/ with Secret history-cold-hmac provisioned out of band."""
    for f in ['clickhouse.yaml', 'history-feed.yaml']:
        docs += [d for d in yaml.safe_load_all((prep.HISTORY_DIR / f).read_text()) if d]
    prep.history_store(docs, hot_days)
    if registry:
        lock = json.loads((ROOT / 'scripts/gcp-compsci/public-image-lock.json').read_text())
        for d in docs:
            pod, name = prep.podspec(d), d['metadata']['name']
            if d['kind'] == 'StorageClass' and name == prep.HISTORY_CLASS:
                d['metadata']['name'] = 'tht-' + name; d['reclaimPolicy'] = 'Delete'
            if name in ('clickhouse', 'history-feed') and pod:
                pod['nodeSelector'] = {'cloud.google.com/gke-nodepool': pool}
                pod['tolerations'] = [{'key': 'traderx-' + pool.removesuffix('-pool'), 'operator': 'Equal', 'value': 'true',
                                       'effect': 'NoSchedule'}]
                c = pod['containers'][0]
                if name == 'clickhouse':
                    c['image'] = lock[c['image']]['reference']
                    d['spec']['volumeClaimTemplates'][0]['spec']['storageClassName'] = 'tht-' + prep.HISTORY_CLASS
                    if hot_days > 0:
                        prep.set_env(c, {'HISTORY_COLD_ENDPOINT': f'https://storage.googleapis.com/{cold_bucket}/{ns}/'})
                else:
                    c['image'] = registry.rstrip('/') + '/history-feed:' + tag
        docs.append({'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'nats-broker'},
                     'spec': {'selector': {'app': 'nats'}, 'ports': [{'name': 'client', 'port': 4222, 'targetPort': 4222}]}})
        return
    docs[:] = [d for d in docs if not (d['kind'] == 'StorageClass' and d['metadata']['name'] == prep.HISTORY_CLASS)]
    for d in docs:
        pod, name = prep.podspec(d), d['metadata']['name']
        if name == 'clickhouse' and d['kind'] == 'StatefulSet':
            d['spec']['volumeClaimTemplates'][0]['spec'].pop('storageClassName')
            d['spec']['volumeClaimTemplates'][0]['spec']['resources']['requests']['storage'] = '5Gi'
            c = pod['containers'][0]; c['resources'] = {'requests': {'cpu': '500m', 'memory': '1Gi'}, 'limits': {'memory': '3Gi'}}
            if hot_days > 0:
                prep.set_env(c, {'HISTORY_COLD_ENDPOINT': 'http://s3mock:9090/traderx-history/traderx/'})
        if name == 'history-feed' and d['kind'] == 'Deployment':
            c = pod['containers'][0]; c['image'] = 'traderx/history-feed:' + tag
            c['resources'] = {'requests': {'cpu': '250m', 'memory': '512Mi'}, 'limits': {'memory': '1Gi'}}
    # production's alias (prepare.py renders nats-broker -> app nats)
    docs.append({'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'nats-broker'},
                 'spec': {'selector': {'app': 'nats'}, 'ports': [{'name': 'client', 'port': 4222, 'targetPort': 4222}]}})
    if hot_days > 0:   # S3Mock takes any key; the Secret is the one prepare.py names
        docs.append({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'history-cold-hmac'},
                     'stringData': {'access-id': 'kind', 'secret': secrets.token_hex(16)}})
        docs.append({'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 's3mock'},
                     'spec': {'selector': {'app': 's3mock'}, 'ports': [{'name': 'http', 'port': 9090}]}})
        docs.append({'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': {'name': 's3mock'},
                     'spec': {'replicas': 1, 'selector': {'matchLabels': {'app': 's3mock'}},
                              'template': {'metadata': {'labels': {'app': 's3mock'}}, 'spec': {'containers': [
                                  {'name': 's3mock', 'image': 'adobe/s3mock:3.12.0', 'imagePullPolicy': 'IfNotPresent',
                                   'env': [{'name': 'initialBuckets', 'value': 'traderx-history'}, {'name': 'root', 'value': '/data'}],
                                   'ports': [{'containerPort': 9090}]}]}}}})


def render(tag, partitions, gateways, ns='traderx', registry='', pool='', with_history=False, hot_days=0,
           history_pool='', cold_bucket='', with_eod=False, binary=False, tp_replicas=1, throughput=False):
    docs = []
    for f in ['statefulset.yaml', 'nats.yaml', 'eod-price-db.yaml', 'trade-processor.yaml', 'gateway.yaml']:
        docs += [d for d in yaml.safe_load_all((K / f).read_text()) if d]
    chain = {'position-service'} if with_history else set()
    if with_eod: chain.add('price-publisher')
    docs += [d for d in yaml.safe_load_all((K / 'eod-chain.yaml').read_text()) if d and d['metadata']['name'] in chain]
    if with_eod:
        docs += [d for d in yaml.safe_load_all((K / 'risk-extract.yaml').read_text()) if d]
    init = ROOT / 'generated/code/target-generated/kubernetes-runtime/manifests/base/database-init-configmap.yaml'
    docs += [d for d in yaml.safe_load_all(init.read_text()) if d]
    docs = [d for d in docs if d['kind'] != 'Secret']
    docs.append({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'auth-secrets'}, 'stringData': {
        k: secrets.token_hex(32) for k in ['jwt-secret', 'dev-token-master-secret', 'risk-control-token', 'fix-session-password',
                                           'cluster-client-secret']}})
    docs.append({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'mariadb-credentials'},
                 'stringData': {'username': 'traderx', 'password': secrets.token_hex(16), 'root-password': secrets.token_hex(16)}})
    for d in docs:
        d['metadata']['namespace'] = ns
        pod = prep.podspec(d)
        if not pod:
            continue
        for c in pod.get('containers', []) + pod.get('initContainers', []):
            if c['image'].startswith(('traderx/cluster-node:', 'traderx/trade-processor:', 'traderx/position-service:',
                                      'traderx/price-publisher:')):
                c['image'] = c['image'].split(':')[0] + ':' + tag
                if registry:   # GKE: traderx/<name> -> <registry>/<name>
                    c['image'] = registry.rstrip('/') + '/' + c['image'].split('/', 1)[1]
                c['imagePullPolicy'] = 'IfNotPresent'
            c['env'] = [e for e in c.get('env', []) if e['name'] not in ('OTEL_TRACES',)]
        if pool:   # GKE: pin to one tainted pool (traderx-<pool minus -pool>), away from production's nodes
            pod['nodeSelector'] = {'cloud.google.com/gke-nodepool': pool}
            pod['tolerations'] = [{'key': 'traderx-' + pool.removesuffix('-pool'), 'operator': 'Equal', 'value': 'true',
                                   'effect': 'NoSchedule'}]
        name = d['metadata']['name']
        if name == 'cluster-gateway' and d['kind'] == 'Deployment':
            d['spec']['replicas'] = gateways
            c = pod['containers'][0]
            prep.set_env(c, {'FIX_SESSION_STORE': 'jetstream', 'ORDER_READMODEL_URL': 'http://trade-processor:18091'})
            c['env'] = [e for e in c['env'] if e['name'] not in ('FIX_SESSION_PASSWORD', 'FIX_SESSION_AUTH', 'RISK_CONTROL_TOKEN')]
            c['env'] += [{'name': 'FIX_SESSION_PASSWORD', 'valueFrom': {'secretKeyRef': {'name': 'auth-secrets', 'key': 'fix-session-password'}}},
                         {'name': 'FIX_SESSION_AUTH', 'value': 'CLIENT1:$(FIX_SESSION_PASSWORD):*'},
                         {'name': 'RISK_CONTROL_TOKEN', 'valueFrom': {'secretKeyRef': {'name': 'auth-secrets', 'key': 'risk-control-token'}}}]
        secret = lambda n, k: {'valueFrom': {'secretKeyRef': {'name': n, 'key': k}}}
        if name == 'price-publisher' and d['kind'] == 'Deployment':   # the rig's operator token, not the dev literal
            c = pod['containers'][0]
            c['env'] = [e for e in c['env'] if e['name'] != 'RISK_CONTROL_TOKEN'] + [{'name': 'RISK_CONTROL_TOKEN', **secret('auth-secrets', 'risk-control-token')}]
        if name == 'risk-extract' and d['kind'] == 'Deployment':      # the rig's random database credentials
            c = pod['containers'][0]
            c['env'] = [e for e in c['env'] if e['name'] not in ('RISK_EXTRACT_JDBC_USER', 'RISK_EXTRACT_JDBC_PASSWORD')] + [
                {'name': 'RISK_EXTRACT_JDBC_USER', **secret('mariadb-credentials', 'username')},
                {'name': 'RISK_EXTRACT_JDBC_PASSWORD', **secret('mariadb-credentials', 'password')}]
    # production's database credentials and JetStream projection (not the gateway: its half adds the TLS socket,
    # which needs Secret fix-tls; the fill feed is set below instead)
    prep.configure_private_runtime([d for d in docs if d['metadata']['name'] in ('trade-processor', 'eod-price-db')])
    for d in docs:
        pod = prep.podspec(d)
        if d['kind'] == 'Deployment' and d['metadata']['name'] == 'cluster-gateway':
            prep.set_env(pod['containers'][0], {'FIX_FILL_FEED': 'jetstream'})
        if d['kind'] == 'StatefulSet' and d['metadata']['name'] == 'order-matcher-cluster':
            prep.set_env(pod['containers'][0], {'BRIDGE_PARTITIONS': str(partitions)})
        if d['kind'] == 'Deployment' and d['metadata']['name'] == 'trade-processor':
            prep.set_env(pod['containers'][0], {
                'PROJECTION_FEED_MODE': 'jetstream', 'PROJECTION_FEED_PARTITIONS': str(partitions),
                'PROJECTION_FEED_STREAM_LAYOUT': 'partition', 'TRADE_PROJECTION_MODE': 'snapshot',
                'ORDER_FEED_PERSIST': 'false', 'ORDER_VIEW_SNAPSHOT_MS': '5000'})
    if binary:   # the throughput run's entry (prepare.add_binary_port), for binary-load.mjs in a pod
        prep.add_binary_port(docs, 18140)
    if throughput:   # the throughput run's members (prepare.throughput_members): egress coalescing, packed snapshots
        prep.throughput_members(docs)
        for d in docs:   # and production's gateway size (prepare.py, FR-PRG-06): kind's 1-CPU limit throttles the owner
            if d['kind'] == 'Deployment' and d['metadata']['name'] == 'cluster-gateway':
                prep.podspec(d)['containers'][0]['resources'] = {'requests': {'cpu': '4', 'memory': '2Gi'},
                                                                 'limits': {'cpu': '4', 'memory': '2Gi'}}
    if with_history:
        history(docs, tag, hot_days, registry, history_pool, cold_bucket, ns)
    prep.shard_read_model(docs, tp_replicas)
    for d in docs: d['metadata']['namespace'] = ns
    if registry:   # GKE: public images by their locked amd64 digest, as production pins them
        lock = json.loads((ROOT / 'scripts/gcp-compsci/public-image-lock.json').read_text())
        for d in docs:
            for c in (prep.podspec(d) or {}).get('containers', []) + (prep.podspec(d) or {}).get('initContainers', []):
                c['image'] = lock.get(c['image'], {}).get('reference', c['image'])
    for d in docs:
        if d['kind'] == 'StorageClass': d['metadata'].pop('namespace', None)   # cluster-scoped
    if ns != 'traderx':   # the manifests name members by FQDN in traderx: left alone, a rig beside production dials ITS members
        docs = json.loads(json.dumps(docs).replace('.traderx.svc', f'.{ns}.svc'))
    if ns.startswith('pa-proof-'):   # what disposable_rig (lib-yu18.sh) reads before a proof deletes or stops anything
        docs.insert(0, {'apiVersion': 'v1', 'kind': 'Namespace',
                        'metadata': {'name': ns, 'labels': {'traderx-proof': 'disposable'}}})
    return docs


if __name__ == '__main__':
    a = argparse.ArgumentParser()
    a.add_argument('--tag', required=True)
    a.add_argument('--partitions', type=int, default=4)
    a.add_argument('--gateways', type=int, default=1)
    a.add_argument('--ns', default='traderx', help='pa-proof-<x> also renders the namespace, labelled disposable')
    a.add_argument('--registry', default='', help='GKE: image registry prefix for traderx/* images')
    a.add_argument('--pool', default='', help='GKE: node pool to pin every pod to (tolerating its taint)')
    a.add_argument('--history', action='store_true', help='add the history store as production renders it (FR-THT-02)')
    a.add_argument('--history-hot-days', type=int, default=0, help='FR-THT-03 tiering (kind: an S3Mock; GKE: --cold-bucket)')
    a.add_argument('--history-pool', default='', help='GKE: the store and its writer on this pool (production: history-pool)')
    a.add_argument('--cold-bucket', default='', help='GKE: the GCS bucket of the cold disk, prefix <ns>/')
    a.add_argument('--eod', action='store_true', help='add price-publisher and risk-extract (the EOD extract)')
    a.add_argument('--binary', action='store_true', help='add the gateway binary port 18140 (binary-load.mjs, FR-THT-10)')
    a.add_argument('--tp-replicas', type=int, default=1, help='trade-processor replicas, each owning a block of the partitions (FR-DRS-45)')
    a.add_argument('--throughput', action='store_true', help="the throughput run's member settings (egress coalescing; FR-BSP-01's proof)")
    o = a.parse_args()
    if o.registry and (o.history or o.history_hot_days) and not o.history_pool:
        a.error('--history on GKE needs --history-pool')
    if o.registry and o.history_hot_days and not o.cold_bucket:
        a.error('--history-hot-days on GKE needs --cold-bucket')
    yaml.safe_dump_all(render(o.tag, o.partitions, o.gateways, o.ns, o.registry, o.pool,
                              with_history=o.history or o.history_hot_days > 0, hot_days=o.history_hot_days,
                              history_pool=o.history_pool, cold_bucket=o.cold_bucket, with_eod=o.eod, binary=o.binary,
                              tp_replicas=o.tp_replicas, throughput=o.throughput), sys.stdout, sort_keys=False)
