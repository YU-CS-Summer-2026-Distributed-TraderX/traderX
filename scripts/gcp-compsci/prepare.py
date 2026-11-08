#!/usr/bin/env python3
"""Render a private CompSci cold-demo candidate; never contact or apply to a cluster."""
import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import yaml

_spec = importlib.util.spec_from_file_location('prepare_population', Path(__file__).with_name('population.py'))
population = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(population)

ROOT = Path(__file__).resolve().parents[2]
PROJECT = 'project-compsci-484414'
REGISTRY = f'us-east1-docker.pkg.dev/{PROJECT}/traderx-demo/'
NAMESPACE = os.environ.get('TRADERX_NAMESPACE') or 'traderx'  # ponytail: a side namespace for a proof next to the live demo
CUSTOM_WORKLOADS = {'order-matcher-cluster','cluster-gateway','risk-extract',
                    'people-service','account-service','trade-service','trade-processor',
                    'position-service','price-publisher','reference-data','execution-algo-engine',
                    'web-front-end-angular','web-front-end-console','api-explorer'}
# ADR-095: the external FIX-over-TLS entry. Its CompID, its accounts and its port; render-fix-tls-access.py exposes it.
# FR-FTL-07 (2026-11-01, yaakov): every account, as CLIENT1 — a desk account created on the day is tradable from the Mac;
# the session stays gated by its own password, the /32 allowlist and the pinned certificate.
FIX_TLS = {'port': 18143, 'compId': 'EXTQFJ', 'accounts': '*'}
# FR-DA12: the Kdb tab's q service, the traderx-kdb VM (scripts/demo-kdb) by its zonal internal name.
KDB_Q_URL = 'http://traderx-kdb.us-east1-b.c.project-compsci-484414.internal:5050'
# ADR-096 (FR-PRG-11): the rehearsal's load clients, opt-in (--fix-test-clients). Each CompID trades only its own
# dedicated test account; passwords from Secret fix-test-clients (provision-fix-test-clients.py). The production-rig
# clean-up task removes them, their accounts and their trades.
FIX_TEST_ACCOUNTS = [str(9900000 + n) for n in range(2, 9)]


def load_clients(count=7):
    """CLIENT2..CLIENT<count+1>, round-robin over the seven test accounts (a throughput run needs many sessions:
    each tops out near 460 orders/s, FR-PRG-11)."""
    return {f'CLIENT{n}': FIX_TEST_ACCOUNTS[(n - 2) % len(FIX_TEST_ACCOUNTS)] for n in range(2, count + 2)}


FIX_TEST_CLIENTS = load_clients()
BUCKETS = {
    'traderx-505400-risk-extracts': f'{PROJECT}-traderx-risk-extracts',
    'traderx-505400-order-matcher-journal-archive': f'{PROJECT}-traderx-journal-archive',
    'traderx-taq-raw': f'{PROJECT}-traderx-taq-raw',
}

# Initial cold-demo envelopes. These are scheduler/startup budgets, not measured
# production capacity. Live startup/load qualification is a separate gate.
SUPPORT_BUDGETS = {
    'people-service': ('100m', '256Mi', '1', '640Mi'),
    'account-service': ('100m', '256Mi', '1', '640Mi'),
    'trade-service': ('100m', '256Mi', '1', '640Mi'),
    'edge-proxy': ('25m', '32Mi', '500m', '64Mi'),
    'web-front-end-angular': ('200m', '512Mi', '1', '2Gi'),
    'api-explorer': ('25m', '64Mi', '500m', '128Mi'),
    'tempo': ('50m', '128Mi', '500m', '256Mi'),
    'prometheus': ('100m', '256Mi', '1', '512Mi'),
    'loki': ('50m', '128Mi', '500m', '256Mi'),
    'grafana': ('50m', '128Mi', '500m', '256Mi'),
    'otel-collector': ('50m', '128Mi', '500m', '256Mi'),
}

# ADR-096 §1: the production rig at bench parity (hxb f15hf2/b15ho, 400K/s for an hour). Each tier has its own pool
# and taint; gateways run one per node, so the gateway count is the node count.
RIG_POOLS = {'order-matcher-cluster': ('engine-pool', 'traderx-engine'),
             'cluster-gateway': ('gateway-pool', 'traderx-gateway'),
             'nats': ('nats-pool', 'traderx-nats'),
             'clickhouse': ('history-pool', 'traderx-history'),
             'history-feed': ('history-pool', 'traderx-history')}
# Generational ZGC as on the bench, but at production's 12 GB heap, not the bench's 6 GB: with MAX_ACTIVE_BOOKS unset the
# book cap is derived from the heap (ADR-079 §6), and every touched book costs ~7 MB under ZGC. The cap is pinned to the
# 1,024 production has today (12 GB G1), so no heap change can move an apply-path decision (BOOK_CAPACITY).
MEMBER_JVM = '-XX:+UseZGC -XX:+ZGenerational -Xms12g -Xmx12g -XX:+AlwaysPreTouch -Daeron.socket.so_rcvbuf=4194304'
MEMBER_MAX_ACTIVE_BOOKS = '1024'
BOOK_MB_ZGC = 7
# FR-PRG-07: longer than the internal LB takes to mark a terminating gateway's node down (health check 8 s x 3).
GATEWAY_DRAIN_DELAY_MS = '30000'


def secret_env(container, name, secret, key):
    env = container.setdefault('env', [])
    env[:] = [e for e in env if e['name'] != name]
    env.append({'name': name, 'valueFrom': {'secretKeyRef': {'name': secret, 'key': key}}})


def configure_private_runtime(docs):
    for doc in docs:
        pod = podspec(doc)
        if not pod: continue
        name = doc['metadata']['name']
        for c in pod.get('containers', []) + pod.get('initContainers', []):
            for entry in list(c.get('env', [])):
                if entry['name'] in ('DATABASE_DBUSER', 'RISK_EXTRACT_JDBC_USER'):
                    secret_env(c, entry['name'], 'mariadb-credentials', 'username')
                if entry['name'] in ('DATABASE_DBPASS', 'RISK_EXTRACT_JDBC_PASSWORD'):
                    secret_env(c, entry['name'], 'mariadb-credentials', 'password')
            if name == 'eod-price-db':
                for var,key in [('MARIADB_USER','username'), ('MARIADB_PASSWORD','password'),
                                ('MARIADB_ROOT_PASSWORD','root-password')]:
                    secret_env(c, var, 'mariadb-credentials', key)
                if c['name'] == 'mariadb':
                    c['readinessProbe']['exec']['command'] = ['sh','-ec',
                        'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -e "SELECT 1" >/dev/null']
            if name == 'grafana':
                secret_env(c, 'GF_SECURITY_ADMIN_PASSWORD', 'grafana-credentials', 'admin-password')
        if name == 'cluster-gateway':
            # FR-PRG-06/07: one gateway per node, and a grace period that covers the drain (delay + in-flight wait).
            pod['terminationGracePeriodSeconds'] = 60
            pod['affinity'] = {'podAntiAffinity': {'requiredDuringSchedulingIgnoredDuringExecution': [
                {'labelSelector': {'matchLabels': {'app': 'cluster-gateway'}}, 'topologyKey': 'kubernetes.io/hostname'}]}}
        for c in pod.get('containers', []):
            if name == 'order-matcher-cluster':
                # FR-PRG-05: generational ZGC (no G1 stop-the-world pauses), 7 CPUs for the DEDICATED driver's agents, a
                # 4 MiB Aeron receive buffer (the engine pool's rmem_max allows it), and the book cap pinned.
                c['resources'] = {'requests': {'cpu': '7', 'memory': '16Gi'}, 'limits': {'cpu': '7', 'memory': '16Gi'}}
                c['env'] = [e for e in c['env'] if e['name'] != 'MAX_ACTIVE_BOOKS']
                c['env'].append({'name': 'MAX_ACTIVE_BOOKS', 'value': MEMBER_MAX_ACTIVE_BOOKS})
                jvm = next(e for e in c['env'] if e['name'] == 'JAVA_TOOL_OPTIONS')
                kept = [t for t in jvm['value'].split() if not t.startswith(('-Xms', '-Xmx', '-XX:MaxGCPauseMillis', '-XX:+Use'))]
                jvm['value'] = ' '.join([MEMBER_JVM] + kept)
            if name == 'cluster-gateway':
                c['resources'] = {'requests': {'cpu': '4', 'memory': '2Gi'}, 'limits': {'cpu': '4', 'memory': '2Gi'}}
                c['env'] = [e for e in c.get('env', []) if e['name'] != 'GATEWAY_DRAIN_DELAY_MS']
                c['env'].append({'name': 'GATEWAY_DRAIN_DELAY_MS', 'value': GATEWAY_DRAIN_DELAY_MS})
            if name == 'trade-processor':
                # FR-DRS-25: the projection pulls a durable JetStream consumer, so sides booked while it is
                # down are replayed on restart instead of lost. FR-THT-01: the snapshot read model, named, never a
                # default: 889e47be cut production from rows to snapshot silently when the default flipped (2026-11-07).
                # The order view is memory-only with its 5 s SQL snapshot, as production e1107 ran (G3 reads the
                # snapshot's open orders; the smoke reads the view through /accounts/{id}/orders).
                set_env(c, {'PROJECTION_FEED_MODE': 'jetstream', 'TRADE_PROJECTION_MODE': 'snapshot',
                            'ORDER_FEED_PERSIST': 'false', 'ORDER_VIEW_SNAPSHOT_MS': '5000'})
                # FR-PRG-29: Ready means G3's read answers. Without a probe, rollout status returned while it still
                # refused connections (production 2026-11-07); the accounting needs its NATS streams and SQL too.
                c['readinessProbe'] = {'httpGet': {'path': '/v2/projection-feed/accounting', 'port': 18091},
                                       'periodSeconds': 5, 'timeoutSeconds': 10, 'failureThreshold': 3}
            if name == 'cluster-gateway':
                # FR-FIX05 (ADR-084): FIX fill reports read the same durable stream, so a fill this gateway
                # misses is re-read from TRADERX_FEED rather than lost.
                c['env'] = [e for e in c.get('env', []) if e['name'] != 'FIX_FILL_FEED']
                c['env'].append({'name': 'FIX_FILL_FEED', 'value': 'jetstream'})
                # ADR-095: a second, TLS-only socket for the external CompID. Keystore and both passwords come from
                # Secret fix-tls (provision-fix-tls.py). Without the Secret the gateway does not start: no plaintext
                # fallback. Nothing outside the cluster reaches the port until fix-tls-external is applied.
                secret_env(c, 'FIX_EXT_PASSWORD', 'fix-tls', 'external-password')
                secret_env(c, 'FIX_SSL_KEYSTORE_PASSWORD', 'fix-tls', 'keystore-password')
                auth = next(e for e in c['env'] if e['name'] == 'FIX_SESSION_AUTH')
                c['env'].remove(auth)   # after FIX_EXT_PASSWORD: $(VAR) expands only earlier entries
                auth['value'] += f",{FIX_TLS['compId']}:$(FIX_EXT_PASSWORD):{FIX_TLS['accounts']}"
                c['env'] += [auth, {'name': 'FIX_TLS_PORT', 'value': str(FIX_TLS['port'])},
                             {'name': 'FIX_TLS_COMPIDS', 'value': FIX_TLS['compId']},
                             {'name': 'FIX_SSL_KEYSTORE', 'value': '/etc/fix-tls/keystore.p12'}]
                c.setdefault('ports', []).append({'name': 'fix-tls', 'containerPort': FIX_TLS['port']})
                c.setdefault('volumeMounts', []).append({'name': 'fix-tls', 'mountPath': '/etc/fix-tls', 'readOnly': True})
                pod.setdefault('volumes', []).append({'name': 'fix-tls', 'secret': {
                    'secretName': 'fix-tls', 'items': [{'key': 'keystore.p12', 'path': 'keystore.p12'}]}})
            if name == 'web-front-end-angular':
                # The inherited development image CMD names a removed script.
                # This packaged script builds production assets and serves on WEB_SERVICE_PORT.
                c['args'] = ['npm', 'run', 'start']
            if name in SUPPORT_BUDGETS:
                cpu,mem,cap,memcap = SUPPORT_BUDGETS[name]
                c['resources'] = {'requests': {'cpu': cpu, 'memory': mem},
                                  'limits': {'cpu': cap, 'memory': memcap}}
                if name in ('people-service','account-service','trade-service'):
                    jvm_options = '-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError'
                    c.setdefault('env', []).append({'name': 'JAVA_TOOL_OPTIONS', 'value': jvm_options})


def add_fix_test_clients(docs, clients=FIX_TEST_CLIENTS):
    for doc in docs:
        if doc['kind'] == 'Deployment' and doc['metadata']['name'] == 'cluster-gateway':
            c = podspec(doc)['containers'][0]
            env = {e['name']: e for e in c['env']}
            auth = env['FIX_SESSION_AUTH']; c['env'].remove(auth)   # $(VAR) expands only earlier entries
            for compid, account in clients.items():
                secret_env(c, 'FIX_TEST_PASSWORD_' + compid[6:], 'fix-test-clients', compid.lower())
                auth['value'] += f',{compid}:$(FIX_TEST_PASSWORD_{compid[6:]}):{account}'
            env['FIX_SESSION_COMPIDS']['value'] += ',' + ','.join(clients)
            c['env'].append(auth)


def add_binary_port(docs, port):
    """ADR-096 (FR-PRG-13): the throughput run's binary entry, in-cluster only (ClusterIP): binary frames carry the
    account and have no logon, so nothing outside the cluster may reach them."""
    gw = next(d for d in docs if d['kind'] == 'Deployment' and d['metadata']['name'] == 'cluster-gateway')
    c = podspec(gw)['containers'][0]
    c['env'] = [e for e in c['env'] if e['name'] != 'BINARY_ACCEPTOR_PORT'] + [{'name': 'BINARY_ACCEPTOR_PORT', 'value': str(port)}]
    c.setdefault('ports', []).append({'name': 'binary', 'containerPort': port})
    docs.append({'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'order-matcher-gw-binary', 'namespace': NAMESPACE,
                 'labels': {'app': 'cluster-gateway'}},
                 'spec': {'type': 'ClusterIP', 'selector': {'app': 'cluster-gateway'},
                          'ports': [{'name': 'binary', 'port': port, 'targetPort': port}]}})


# ADR-096 (FR-PRG-15): the bench's 450K member settings, all in 00b31d33 and later: egress coalescing (9373b8ef; every
# egress reader splits), packed snapshots with the ring-ordered idempotency walk (bccbc71a, the snapshot-stall fix).
THROUGHPUT_MEMBER_ENV = {'CLUSTER_EGRESS_COALESCE': 'true', 'CLUSTER_SNAPSHOT_PACKED': 'true', 'CLUSTER_SNAPSHOT_RING': 'true'}


def throughput_members(docs):
    sts = next(d for d in docs if d['kind'] == 'StatefulSet' and d['metadata']['name'] == 'order-matcher-cluster')
    c = podspec(sts)['containers'][0]
    c['env'] = [e for e in c['env'] if e['name'] not in THROUGHPUT_MEMBER_ENV] + \
               [{'name': k, 'value': v} for k, v in THROUGHPUT_MEMBER_ENV.items()]


def without_bridges(docs):
    """ADR-096 (FR-PRG-14): a full-throughput run with the members' NATS bridges off (as the bench's 400K hours ran).
    The read model then misses the run's trade sides: record them with `rig.sh unprojected A-B` before the next gate.
    FIX fill reports, which read that feed, stop too; New acks are unaffected."""
    sts = next(d for d in docs if d['kind'] == 'StatefulSet' and d['metadata']['name'] == 'order-matcher-cluster')
    for c in podspec(sts)['containers']:
        c['env'] = [e for e in c.get('env', []) if e['name'] != 'TRADE_BRIDGE_NATS_URL']


# FR-PRG-40 (ADR-079 §3/§6, the bench's B2): ArchivePurgeMain beside each member, purging its archive behind the
# second-latest snapshot. It ships in the member image; it runs as its own container on the same /data and /dev/shm.
# History is discarded, never offloaded: this render takes the deployment decision ADR-079 left open, and only behind
# --archive-purge. The restore gate holds it through ARCHIVE_PURGE_HOLD_FILE while it reads the archive (rig.sh).
# 100m/256Mi requests = limits so the pod stays Guaranteed beside the member's whole CPUs (0.1 CPU held 250K/s on hxb).
ARCHIVE_PURGE_HOLD_FILE = '/data/archive-purge.hold'
ARCHIVE_PURGE_JVM = '-Xmx128m -XX:+UseSerialGC --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED'


def archive_purge(docs):
    sts = next(d for d in docs if d['kind'] == 'StatefulSet' and d['metadata']['name'] == 'order-matcher-cluster')
    pod = podspec(sts)
    member = pod['containers'][0]
    env = {e['name']: e.get('value') for e in member['env']}
    keep = ('CLUSTER_HOSTNAMES', 'CLUSTER_READY_MAX_LAG', 'HEALTH_PORT')
    pod['containers'] = [c for c in pod['containers'] if c['name'] != 'archive-purge'] + [{
        'name': 'archive-purge', 'image': member['image'],
        'command': ['java', '-cp', '/opt/app/classes:/opt/app/lib/*', 'finos.traderx.ordermatcher.cluster.ArchivePurgeMain',
                    env['CLUSTER_BASE_DIR'] + '/cluster', env['CLUSTER_AERON_DIR']],
        'env': [e for e in member['env'] if e['name'] in keep] + [
            {'name': 'CLUSTER_MEMBER_ID', 'valueFrom': {'fieldRef': {'fieldPath': 'metadata.name'}}},
            {'name': 'ARCHIVE_PURGE_DISCARD_HISTORY', 'value': 'true'},
            {'name': 'ARCHIVE_PURGE_HOLD_FILE', 'value': ARCHIVE_PURGE_HOLD_FILE},
            {'name': 'JAVA_TOOL_OPTIONS', 'value': ARCHIVE_PURGE_JVM}],
        'resources': {'requests': {'cpu': '100m', 'memory': '256Mi'}, 'limits': {'cpu': '100m', 'memory': '256Mi'}},
        'volumeMounts': [m for m in member['volumeMounts'] if m['name'] in ('data', 'shm')]}]
    # every exec and log without -c reaches the member, not the agent
    sts['spec']['template']['metadata'].setdefault('annotations', {})['kubectl.kubernetes.io/default-container'] = member['name']


# ADR-097 (FR-PRG-20): production's read model with persistence ON at high rates. The capture stream TRADERX_FEED is the
# system of record (every trade side and order update, durable on the NATS PVC); trades are booked in memory and SQL
# takes a snapshot every 5 s (positions, settlement counts, each account's blotter window, the cursor and the exactly-
# once accounting, FR-DRS-29); orders live in the memory-only view with its own SQL snapshot of open orders (FR-DRS-30).
# P partitions spread both over P pull threads by account, each partition in its own stream (FR-DRS-32: one stream was
# the ceiling). Measured (downstream-at-400k.md §7.12, +1 ms RTT): P=8 on production's nats-pool shape holds 125K
# orders/s with every hop persisting and the exactly-once accounting exact; 150K does not, on c3-standard-8 or -22.
READ_MODEL_PARTITIONS = 8
READ_MODEL_RATE = 125_000            # orders/s the sizing below holds with every hop persisting
READ_MODEL_OUTAGE_MINUTES = 30       # the longest outage the stream must replay (a node replacement + a DB restart)
STREAM_BYTES_PER_ORDER = 462 + 588 * 3 // 2   # stored: one trade side + 1.5 order updates per order (§7.7)
READ_MODEL_POOLS = {'trade-processor': ('readmodel-pool', 'traderx-readmodel'), 'eod-price-db': ('db-pool', 'traderx-db')}
READ_MODEL_TP = {'cpu': '12', 'memory': '24Gi', 'jvm': '-XX:+UseZGC -XX:+ZGenerational -Xms16g -Xmx16g -XX:+ExitOnOutOfMemoryError'}
READ_MODEL_DB = {'cpu': '6', 'memory': '24Gi', 'bufferPool': '16G', 'disk': '200Gi'}
# NATS memory is the base manifest's for every profile (FR-PRG-28, validate_nats_memory), not this one's alone: the rows
# profile ran NATS at 2Gi and it was OOM-killed under the bridges' publish on 2026-10-30.
READ_MODEL_DB_CLASS = 'eod-price-db-fast'
READ_MODEL_DB_CLAIM = 'eod-price-db-fast'
READ_MODEL_DB_CLAIM = '50Gi'   # FR-PRG-37; a claim only grows, so this never goes back down
READ_MODEL_DB_SNAPSHOT = 'eod-price-db-pre-fast'   # taken of eod-price-db-data while the database is at 0


def stream_retention_bytes(rate=READ_MODEL_RATE, minutes=READ_MODEL_OUTAGE_MINUTES):
    return rate * STREAM_BYTES_PER_ORDER * minutes * 60


def set_env(c, values):
    c['env'] = [e for e in c.get('env', []) if e['name'] not in values] + [{'name': k, 'value': v} for k, v in values.items()]


def snapshot_read_model(docs, partitions=READ_MODEL_PARTITIONS):
    """ADR-097 (FR-PRG-20): the members partition both bridges by account, trade-processor runs the snapshot read model
    on its own pool, NATS keeps the outage window, and the database moves to its own pool on a faster disk. The move
    is a copy, not a re-create: the new claim is restored from a VolumeSnapshot of the old one (taken at 0 replicas),
    so the read model the restore gate fingerprints is the one the old disk held."""
    retention = stream_retention_bytes()
    store_gb = -(-retention * 5 // 4 // 10**9)                  # stream + 25% for the EOD streams and compaction
    for doc in docs:
        name, kind = doc['metadata']['name'], doc['kind']
        pod = podspec(doc)
        if kind == 'StatefulSet' and name == 'order-matcher-cluster':
            set_env(pod['containers'][0], {'BRIDGE_PARTITIONS': str(partitions)})
        elif kind == 'Deployment' and name == 'trade-processor':
            c = pod['containers'][0]
            set_env(c, {'PROJECTION_FEED_MODE': 'jetstream', 'PROJECTION_FEED_PARTITIONS': str(partitions),
                        'PROJECTION_FEED_STREAM_LAYOUT': 'partition',
                        'TRADE_PROJECTION_MODE': 'snapshot', 'ORDER_FEED_PERSIST': 'false', 'ORDER_VIEW_SNAPSHOT_MS': '5000',
                        'PROJECTION_FEED_STREAM_MAX_BYTES': str(retention), 'PROJECTION_FEED_STREAM_MAX_AGE_HOURS': '2'})
            jvm = next(e for e in c['env'] if e['name'] == 'JAVA_TOOL_OPTIONS')
            kept = [t for t in jvm['value'].split() if not t.startswith(('-Xms', '-Xmx', '-XX:MaxRAMPercentage', '-XX:+Use',
                                                                          '-XX:+ZGenerational', '-XX:+ExitOnOutOfMemoryError'))]
            jvm['value'] = ' '.join([READ_MODEL_TP['jvm']] + kept)
            c['resources'] = {'requests': {'cpu': READ_MODEL_TP['cpu'], 'memory': READ_MODEL_TP['memory']},
                              'limits': {'cpu': READ_MODEL_TP['cpu'], 'memory': READ_MODEL_TP['memory']}}
        elif kind == 'ConfigMap' and name == 'nats-server-config':
            doc['data']['nats.conf'] = re.sub(r'max_file_store: \S+', f'max_file_store: {store_gb}G', doc['data']['nats.conf'])
        elif kind == 'PersistentVolumeClaim' and name == 'nats-data':
            doc['spec']['resources']['requests']['storage'] = f'{store_gb * 11 // 10 + 1}Gi'
        elif kind == 'Deployment' and name == 'eod-price-db':
            c = next(x for x in pod['containers'] if x['name'] == 'mariadb')
            c['resources'] = {'requests': {'cpu': READ_MODEL_DB['cpu'], 'memory': READ_MODEL_DB['memory']},
                              'limits': {'cpu': READ_MODEL_DB['cpu'], 'memory': READ_MODEL_DB['memory']}}
            c['args'] = [a for a in c.get('args', []) if not a.startswith('--innodb-buffer-pool-size')] + \
                        [f'--innodb-buffer-pool-size={READ_MODEL_DB["bufferPool"]}']
            for v in pod['volumes']:
                if v.get('persistentVolumeClaim', {}).get('claimName') == 'eod-price-db-data':
                    v['persistentVolumeClaim']['claimName'] = READ_MODEL_DB_CLAIM
        if pod and name in READ_MODEL_POOLS and kind == 'Deployment':
            pool, taint = READ_MODEL_POOLS[name]
            pod['nodeSelector'] = {'cloud.google.com/gke-nodepool': pool}
            pod['tolerations'] = [{'key': taint, 'operator': 'Equal', 'value': 'true', 'effect': 'NoSchedule'}]
    # Hyperdisk Balanced with provisioned IOPS/throughput: the DB pool is C4D, which attaches Hyperdisk only.
    docs.append({'apiVersion': 'storage.k8s.io/v1', 'kind': 'StorageClass', 'metadata': {'name': READ_MODEL_DB_CLASS},
                 'provisioner': 'pd.csi.storage.gke.io',
                 'parameters': {'type': 'hyperdisk-balanced', 'provisioned-iops-on-create': '12000',
                                'provisioned-throughput-on-create': '400Mi', 'csi.storage.k8s.io/fstype': 'ext4'},
                 'volumeBindingMode': 'WaitForFirstConsumer', 'reclaimPolicy': 'Retain', 'allowVolumeExpansion': True})
    docs.append({'apiVersion': 'snapshot.storage.k8s.io/v1', 'kind': 'VolumeSnapshotClass',
                 'metadata': {'name': 'traderx-pd-snapshots'}, 'driver': 'pd.csi.storage.gke.io', 'deletionPolicy': 'Retain'})
    docs.append({'apiVersion': 'snapshot.storage.k8s.io/v1', 'kind': 'VolumeSnapshot',
                 'metadata': {'name': READ_MODEL_DB_SNAPSHOT, 'namespace': NAMESPACE},
                 'spec': {'volumeSnapshotClassName': 'traderx-pd-snapshots',
                          'source': {'persistentVolumeClaimName': 'eod-price-db-data'}}})
    docs.append({'apiVersion': 'v1', 'kind': 'PersistentVolumeClaim',
                 'metadata': {'name': READ_MODEL_DB_CLAIM, 'namespace': NAMESPACE},
                 'spec': {'accessModes': ['ReadWriteOnce'], 'storageClassName': READ_MODEL_DB_CLASS,
                          'resources': {'requests': {'storage': READ_MODEL_DB['disk']}},
                          'dataSource': {'apiGroup': 'snapshot.storage.k8s.io', 'kind': 'VolumeSnapshot',
                                         'name': READ_MODEL_DB_SNAPSHOT}}})


# FR-THT-02 (ADR-102): the trade history's system of record, ClickHouse fed by history-feed, alone on history-pool
# (c4d-standard-8, 8 vCPU / 32 GB). ClickHouse kept 1M rows/s on 3.8 cores in the bake-off (ADR-098); the read model is
# sized for 125K orders/s, ~310K rows/s. Disk: ~131 GiB per full-rate hour (ADR-098), so ~41 GiB/h at 125K/s, and
# 200Gi holds ~4.9 h of it before FR-THT-03 moves old days to GCS. Hyperdisk Balanced: C4D attaches no PD.
HISTORY_DIR = ROOT / 'specs/YU18-risk-integration/generation/runtime-overrides/history-feed/k8s'
HISTORY_STORE = {'cpu': '4', 'memory': '16Gi', 'disk': '200Gi'}
HISTORY_FEED = {'cpu': '2', 'memory': '4Gi'}
HISTORY_CLASS = 'clickhouse-hot'
HISTORY_FEED_ENV = {'PROJECTION_FEED_STREAM': 'TRADERX_FEED', 'PROJECTION_FEED_PARTITIONS': '1',
                    'PROJECTION_FEED_STREAM_LAYOUT': 'one'}   # both services' defaults
# FR-THT-03 on GKE: GCS through its XML API with an HMAC key (Secret history-cold-hmac), one prefix per namespace.
HISTORY_COLD_BUCKET = f'{PROJECT}-traderx-history'
HISTORY_COLD_TIERED = '/etc/clickhouse-server/config.d/tiered.xml'


def history_store(docs, hot_days=0):
    """FR-THT-02: ClickHouse and history-feed sized and guaranteed, the store on its own retained class, history-feed
    on exactly trade-processor's streams. FR-THT-03 with hot_days > 0: the `tiered` policy, cold volume on GCS."""
    tp = next(d for d in docs if d['kind'] == 'Deployment' and d['metadata']['name'] == 'trade-processor')
    feed = {e['name']: e.get('value') for e in podspec(tp)['containers'][0].get('env', [])}
    for doc in docs:
        name, pod = doc['metadata']['name'], podspec(doc)
        if doc['kind'] == 'StatefulSet' and name == 'clickhouse':
            c = pod['containers'][0]
            c['resources'] = {'requests': {'cpu': HISTORY_STORE['cpu'], 'memory': HISTORY_STORE['memory']},
                              'limits': {'cpu': HISTORY_STORE['cpu'], 'memory': HISTORY_STORE['memory']}}
            claim = doc['spec']['volumeClaimTemplates'][0]['spec']
            claim['storageClassName'] = HISTORY_CLASS
            claim['resources']['requests']['storage'] = HISTORY_STORE['disk']
            if hot_days > 0:
                set_env(c, {'HISTORY_COLD_ENDPOINT': f'https://storage.googleapis.com/{HISTORY_COLD_BUCKET}/{NAMESPACE}/'})
                secret_env(c, 'HISTORY_COLD_KEY_ID', 'history-cold-hmac', 'access-id')
                secret_env(c, 'HISTORY_COLD_SECRET', 'history-cold-hmac', 'secret')
                c.setdefault('volumeMounts', []).append({'name': 'tiered', 'mountPath': HISTORY_COLD_TIERED, 'subPath': 'tiered.xml'})
                pod.setdefault('volumes', []).append({'name': 'tiered', 'configMap': {'name': 'clickhouse-tiered'}})
        elif doc['kind'] == 'Deployment' and name == 'history-feed':
            c = pod['containers'][0]
            c['resources'] = {'requests': {'cpu': HISTORY_FEED['cpu'], 'memory': HISTORY_FEED['memory']},
                              'limits': {'cpu': HISTORY_FEED['cpu'], 'memory': HISTORY_FEED['memory']}}
            set_env(c, {k: feed.get(k) or v for k, v in HISTORY_FEED_ENV.items()} | {'HISTORY_HOT_DAYS': str(hot_days)})
    if hot_days > 0:
        docs.append({'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'clickhouse-tiered', 'namespace': NAMESPACE},
                     'data': {'tiered.xml': (HISTORY_DIR / 'clickhouse-tiered.xml').read_text()}})
    docs.append({'apiVersion': 'storage.k8s.io/v1', 'kind': 'StorageClass', 'metadata': {'name': HISTORY_CLASS},
                 'provisioner': 'pd.csi.storage.gke.io',
                 'parameters': {'type': 'hyperdisk-balanced', 'csi.storage.k8s.io/fstype': 'ext4'},
                 'volumeBindingMode': 'WaitForFirstConsumer', 'reclaimPolicy': 'Retain', 'allowVolumeExpansion': True})
    # The store has no user setup (in-cluster only): only its writer, and trade-processor (the rig's G3h reads
    # through it), reach the port.
    docs.append({'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
                 'metadata': {'name': 'clickhouse-clients', 'namespace': NAMESPACE},
                 'spec': {'podSelector': {'matchLabels': {'app': 'clickhouse'}}, 'policyTypes': ['Ingress'],
                          'ingress': [{'from': [{'podSelector': {'matchLabels': {'app': a}}} for a in ('history-feed', 'trade-processor')],
                                       'ports': [{'port': 8123}]}]}})


def validate_history_store(docs, require):
    """FR-THT-02: the store and its writer rendered, sized, retained, and on trade-processor's streams."""
    store = next((d for d in docs if d['kind'] == 'StatefulSet' and d['metadata']['name'] == 'clickhouse'), None)
    writer = next((d for d in docs if d['kind'] == 'Deployment' and d['metadata']['name'] == 'history-feed'), None)
    require(store and writer, 'the history store (sts/clickhouse) and its writer (deploy/history-feed) are not rendered (FR-THT-02)')
    for doc in (store, writer):
        res = podspec(doc)['containers'][0].get('resources', {})
        require(res.get('requests') and res.get('requests') == res.get('limits')
                and all(res['requests'].get(k) for k in ('cpu', 'memory')),
                f'{doc["metadata"]["name"]} is not sized: guaranteed cpu and memory, requests equal to limits (FR-THT-02)')
    claim = store['spec']['volumeClaimTemplates'][0]['spec']
    classes = {d['metadata']['name']: d for d in docs if d['kind'] == 'StorageClass'}
    require(claim.get('storageClassName') in classes and claim['resources']['requests'].get('storage')
            and classes[claim['storageClassName']].get('reclaimPolicy') == 'Retain',
            'the history store has no sized claim on a rendered, retained storage class (FR-THT-02)')
    require(writer['spec'].get('replicas') == 1 and writer['spec'].get('strategy', {}).get('type') == 'Recreate',
            'history-feed must be one Recreate replica: its lanes are not fenced against a second writer (ADR-098)')
    tp = next(d for d in docs if d['kind'] == 'Deployment' and d['metadata']['name'] == 'trade-processor')
    env = lambda d: {e['name']: e.get('value') for e in podspec(d)['containers'][0].get('env', [])}
    want = {k: env(tp).get(k) or v for k, v in HISTORY_FEED_ENV.items()}
    require({k: env(writer).get(k) or v for k, v in HISTORY_FEED_ENV.items()} == want,
            "history-feed does not read trade-processor's streams and partitions (ADR-098 §2)")
    if int(env(writer).get('HISTORY_HOT_DAYS') or 0) > 0:
        c = podspec(store)['containers'][0]
        names = {e['name'] for e in c.get('env', [])}
        require({'HISTORY_COLD_ENDPOINT', 'HISTORY_COLD_KEY_ID', 'HISTORY_COLD_SECRET'} <= names
                and any(m['mountPath'] == HISTORY_COLD_TIERED for m in c.get('volumeMounts', [])),
                'HISTORY_HOT_DAYS > 0 without the tiered policy and its cold disk on ClickHouse (FR-THT-03)')


# FR-PRG-28: NATS's memory, as the pod and NATS itself each see it. NATS runs alone on nats-pool's c3-standard-8: 32 GiB,
# GKE allocatable ~28.3 GiB (its reservation: 25% of the first 4 GiB, 20% of the next 4, 10% of the next 8, 6% above,
# plus 100 MiB eviction), less ~2 GiB kept for the node's DaemonSets.
NATS_NODE_BUDGET = 26 * 2**30


def size_bytes(v):
    """A Kubernetes quantity (24Gi), a GOMEMLIMIT (20GiB) or a NATS size (256MB, 1GB; NATS reads MB/GB as binary)."""
    m = re.fullmatch(r'(\d+)\s*([KMGT]?)(i?B?|B)?', v.strip(), re.I)
    if not m:
        raise ValueError(f'unreadable size {v!r}')
    return int(m.group(1)) * 1024 ** ' KMGT'.index((m.group(2) or ' ').upper())


def validate_nats_memory(docs, require):
    """FR-PRG-28: the pod's memory, the Go heap limit and NATS's own limits agree, so NATS is never OOM-killed for holding
    what it was configured to hold. 2026-10-30: limit 2Gi, NATS's memory store unset (75% of the host, 23.5 GB), and a
    1GB ingest queue under ~150K trades/s: OOM-killed 7 times, the FIX session store down with it, ~89.7M sides lost."""
    nats = next(d for d in docs if d['kind'] == 'Deployment' and d['metadata']['name'] == 'nats')
    c = podspec(nats)['containers'][0]
    res = c.get('resources', {})
    limit = size_bytes(res.get('limits', {}).get('memory', '0'))
    gomem = next((e.get('value') for e in c.get('env', []) if e['name'] == 'GOMEMLIMIT'), None)
    conf = next(d for d in docs if d['kind'] == 'ConfigMap' and d['metadata']['name'] == 'nats-server-config')['data']['nats.conf']
    def conf_size(key):
        m = re.search(rf'^\s*{key}:\s*(\S+)', conf, re.M)
        return size_bytes(m.group(1)) if m else None
    store, queue = conf_size('max_memory_store'), conf_size('max_buffered_size')
    require(0 < limit <= NATS_NODE_BUDGET and res.get('requests', {}).get('memory') == res['limits']['memory'],
            'NATS memory: the limit must fit nats-pool\'s node with headroom and equal the request (FR-PRG-28)')
    require(gomem is not None and size_bytes(gomem) * 100 <= limit * 85,
            'NATS memory: GOMEMLIMIT must hold the Go heap at or under 85% of the pod limit (FR-PRG-28)')
    require(store is not None and queue is not None and (store + queue) * 2 <= size_bytes(gomem or '0'),
            'NATS memory: max_memory_store must be explicit (unset is 75% of the host), and it plus the ingest queue '
            '(max_buffered_size) must fit in half the Go heap limit (FR-PRG-28)')


def validate_read_model(docs, require):
    """FR-PRG-20 preconditions: the parts of the snapshot read model only work together."""
    def env(name, kind='Deployment'):
        d = next((d for d in docs if d['kind'] == kind and d['metadata']['name'] == name), None)
        return {e['name']: e.get('value') for e in podspec(d)['containers'][0].get('env', [])} if d else {}
    tp, members = env('trade-processor'), env('order-matcher-cluster', 'StatefulSet')
    require(tp.get('TRADE_PROJECTION_MODE') == 'snapshot',
            'trade-processor must name TRADE_PROJECTION_MODE=snapshot: a default changes the read model unseen (FR-THT-01)')
    require(int(tp.get('ORDER_VIEW_SNAPSHOT_MS') or 0) > 0,
            "no order view snapshot (ORDER_VIEW_SNAPSHOT_MS): the restore gate's G3 cannot check open orders, and a "
            "memory-only view loses every order older than the replay window on restart (FR-DRS-30, FR-PRG-22)")
    if tp.get('PROJECTION_FEED_STREAM_LAYOUT') != 'partition' and tp.get('PROJECTION_FEED_PARTITIONS') is None:
        return   # the default profile: one partition, one stream; FR-PRG-20's sizing does not apply
    require(tp.get('PROJECTION_FEED_MODE') == 'jetstream', 'the trade store reads the stream: PROJECTION_FEED_MODE=jetstream (FR-PRG-20)')
    require(tp.get('PROJECTION_FEED_STREAM_LAYOUT') == 'partition',
            'one capture stream holds ~50K orders/s; the profile is sized for one stream per partition (FR-DRS-32, ADR-097)')
    require(tp.get('PROJECTION_FEED_PARTITIONS') == members.get('BRIDGE_PARTITIONS', '1'),
            "trade-processor's partitions differ from the members' BRIDGE_PARTITIONS: a partition would never be read (FR-PRG-20)")
    conf = next(d for d in docs if d['kind'] == 'ConfigMap' and d['metadata']['name'] == 'nats-server-config')['data']['nats.conf']
    store = int(re.search(r'max_file_store: (\d+)G', conf).group(1)) * 10**9
    claim = next(d for d in docs if d['kind'] == 'PersistentVolumeClaim' and d['metadata']['name'] == 'nats-data')
    require(int(tp.get('PROJECTION_FEED_STREAM_MAX_BYTES') or 0) < store
            and int(claim['spec']['resources']['requests']['storage'][:-2]) * 2**30 > store,
            'the stream limit must sit under max_file_store, and max_file_store under the NATS claim (FR-PRG-20)')
    db = next(d for d in docs if d['kind'] == 'Deployment' and d['metadata']['name'] == 'eod-price-db')
    claims = [v['persistentVolumeClaim']['claimName'] for v in podspec(db)['volumes'] if 'persistentVolumeClaim' in v]
    restored = next((d for d in docs if d['kind'] == 'PersistentVolumeClaim' and d['metadata']['name'] in claims), None)
    require(restored is not None and (restored['metadata']['name'] == 'eod-price-db-data'
                                      or restored['spec'].get('dataSource', {}).get('kind') == 'VolumeSnapshot'),
            'the database moved to a new claim that is not restored from the old one: it would start empty (FR-PRG-20)')


def refuse_sequenced_binary(docs, declared):
    """ADR-096 §6: in front of members built from an older commit (pre-ADR-090), a sequenced binary NEW must never
    reach the cluster, so the gateway refuses the flag (BINARY_SEQUENCED=refuse)."""
    if not population.workload_image(declared, 'order-matcher-cluster'):
        return
    for doc in docs:
        if doc['kind'] == 'Deployment' and doc['metadata']['name'] == 'cluster-gateway':
            c = podspec(doc)['containers'][0]
            c['env'] = [e for e in c['env'] if e['name'] != 'BINARY_SEQUENCED'] + [{'name': 'BINARY_SEQUENCED', 'value': 'refuse'}]


def apply_population(docs, declared, tag):
    """ADR-096 §6: a workload with a declared exception runs its own image repository (built from its own commit)."""
    for doc in docs:
        image = population.workload_image(declared, doc['metadata']['name']) if podspec(doc) else None
        if not image: continue
        for c in podspec(doc).get('containers', []) + podspec(doc).get('initContainers', []):
            if c['image'].startswith(REGISTRY):
                c['image'] = REGISTRY + image + ':' + tag


def pin_images(docs, published, public):
    require_ref = lambda ref: isinstance(ref,str) and re.fullmatch(r'[^\s@]+@sha256:[0-9a-f]{64}',ref)
    private = published['images']
    used = set()
    for doc in docs:
        pod = podspec(doc)
        if not pod: continue
        for c in pod.get('containers', []) + pod.get('initContainers', []):
            ref = c['image']
            if ref.startswith(REGISTRY):
                name = ref[len(REGISTRY):].split(':')[0]
                item = private[name]
                target = item['reference']
                assert target.startswith(REGISTRY+name+'@'), 'private repository mismatch'
                assert target == item['registryMetadata']['image_summary']['fully_qualified_digest'], 'registry digest mismatch'
                used.add(name)
            else:
                assert ref in public, f'public image {ref} has no locked digest in public-image-lock.json'
                item = public[ref]; target = item['reference']
                digest = target.split('@',1)[-1]
                assert any(m['digest']==digest and m.get('platform',{})=={'architecture':'amd64','os':'linux'}
                           for m in item['manifest']['manifests']), 'public image is not the recorded amd64 manifest'
            assert require_ref(target), 'immutable image reference required'
            c['image'] = target
    assert used == set(private), 'private image coverage differs from published inventory'


def read(path):
    return [d for d in yaml.safe_load_all(path.read_text()) if d]


def podspec(doc):
    if doc['kind'] in ('Deployment', 'StatefulSet', 'Job', 'DaemonSet'):
        return doc['spec']['template']['spec']
    return None


def validate(docs, declared=None):
    def require(value,message):
        if not value: raise AssertionError(message)
    # ADR-096 §6: each declared exception runs on its workload and nowhere else; no other workload leaves the defaults.
    exceptions = population.exceptions(declared or population.load())
    for doc in docs:
        pod = podspec(doc)
        if not pod: continue
        private = [c['image'][len(REGISTRY):].split('@')[0].split(':')[0]
                   for c in pod.get('containers', []) + pod.get('initContainers', []) if c['image'].startswith(REGISTRY)]
        mine = population.workload_image(declared or population.load(), doc['metadata']['name'])
        require(all(name == mine for name in private) if mine else not any(name in exceptions for name in private),
                f'{doc["metadata"]["name"]}: image differs from the declared population (ADR-096 §6)')
        if doc['kind'] == 'Deployment' and doc['metadata']['name'] == 'cluster-gateway' \
                and population.workload_image(declared or population.load(), 'order-matcher-cluster'):
            genv = {e['name']: e.get('value') for c in pod['containers'] for e in c.get('env', [])}
            require(genv.get('BINARY_SEQUENCED') == 'refuse',
                    'gateway in front of older members without BINARY_SEQUENCED=refuse (ADR-096 §6)')
    for doc in docs:   # FR-PRG-13: the binary entry is never exposed outside the cluster
        if doc['kind'] == 'Service' and any(pt.get('name') == 'binary' for pt in doc['spec'].get('ports', [])):
            require(doc['spec'].get('type', 'ClusterIP') == 'ClusterIP', 'binary port exposed outside the cluster (FR-PRG-13)')
    require(docs, 'empty manifest population')
    ids = [(d['kind'], d['metadata']['name']) for d in docs]
    require(len(ids) == len(set(ids)), 'duplicate resource identity')
    require(('StatefulSet', 'order-matcher-cluster') in ids, 'missing engine')
    require(('Deployment', 'web-front-end-console') in ids, 'missing console')
    services={d['metadata']['name'] for d in docs if d['kind']=='Service'}
    proxy=next(d for d in docs if d['kind']=='ConfigMap' and d['metadata']['name']=='edge-proxy-config')
    for host in re.findall(r'proxy_pass http://([a-z][a-z0-9-]*):',proxy['data']['default.conf']):
        require(host in services, f'proxy upstream has no Service: {host}')
    for doc in docs:
        require(doc['kind'] not in ('Secret', 'Ingress', 'CronJob'), 'unexpected secret/public ingress/automatic job')
        if doc['kind'] == 'Service':
            require(doc['spec'].get('type', 'ClusterIP') == 'ClusterIP', 'public service')
        pod = podspec(doc)
        if pod:
            for c in pod.get('containers', []) + pod.get('initContainers', []):
                image = c['image']
                if doc['metadata']['name'] in CUSTOM_WORKLOADS:
                    require(image.startswith(REGISTRY), 'custom workload image has no CompSci registry identity')
                require('traderx-505400' not in image and not image.startswith('traderx/'), 'old/local image')
                if 'docker.pkg.dev' in image:
                    require(image.startswith(REGISTRY), 'wrong project/repository')
                require(':latest' not in image, 'floating latest image')
                for entry in c.get('env', []):
                    if entry['name'] in ('MARIADB_PASSWORD','MARIADB_ROOT_PASSWORD','DATABASE_DBPASS',
                        'RISK_EXTRACT_JDBC_PASSWORD','GF_SECURITY_ADMIN_PASSWORD','RISK_CONTROL_TOKEN',
                        'ADMIN_PASSWORD_HASH','AUTH_SESSION_SECRET','AUTH_JWT_SECRET','AUTH_MASTER_SECRET',
                        'FIX_SESSION_PASSWORD','FIX_EXT_PASSWORD','FIX_SSL_KEYSTORE_PASSWORD'):
                        require('value' not in entry and 'secretKeyRef' in entry.get('valueFrom', {}),
                                'private credential must use a Secret reference')
        # ADR-082: a FIX acceptor on this profile authenticates its sessions. ALLOW_DEV_SECRETS is the only
        # opt-out and no workload here may carry it; FIX_SESSION_AUTH names the Secret-backed password.
        for c in (pod or {}).get('containers', []):
            env = {e['name']: e for e in c.get('env', [])}
            require('ALLOW_DEV_SECRETS' not in env, 'development-secret opt-out on the CompSci profile')
            if 'FIX_ACCEPTOR_PORT' in env:
                names = [e['name'] for e in c['env']]
                require('FIX_SESSION_AUTH' in env and '$(FIX_SESSION_PASSWORD)' in env['FIX_SESSION_AUTH'].get('value', '')
                        and 'FIX_SESSION_PASSWORD' in names
                        and names.index('FIX_SESSION_PASSWORD') < names.index('FIX_SESSION_AUTH'),
                        'FIX acceptor without Secret-backed session credentials')
                # Every CompID's password is a Secret-backed variable defined before FIX_SESSION_AUTH, and only
                # CLIENT1 may trade every account (*); any other CompID names its accounts (ADR-095, FR-PRG-11).
                for entry in env['FIX_SESSION_AUTH'].get('value', '').split(','):
                    compid, password, accounts = (entry.split(':') + ['', ''])[:3]
                    var = re.fullmatch(r'\$\((\w+)\)', password)
                    require(var and var.group(1) in names and names.index(var.group(1)) < names.index('FIX_SESSION_AUTH')
                            and 'secretKeyRef' in env[var.group(1)].get('valueFrom', {}),
                            f'FIX CompID {compid} without a Secret-backed password')
                    # CLIENT1 and the external EXTQFJ (FR-FTL-07) trade every account; the load clients only their own.
                    require(accounts == '*' if compid in ('CLIENT1', FIX_TLS['compId']) else re.fullmatch(r'\d+(\|\d+)*', accounts),
                            f'FIX CompID {compid} is not bound to its accounts')
        # YU04 (FR-IMRG32/33): the gateway consumes reference-data's control feed, or a new security
        # reaches it only on restart.
        if doc['kind'] == 'Deployment' and doc['metadata']['name'] == 'cluster-gateway':
            env = {e['name']: e.get('value') for c in pod['containers'] for e in c.get('env', [])}
            require(env.get('CONTROL_FEED_SUBSCRIBER') == '1', 'gateway does not subscribe to the control feed (YU04)')
            require(env.get('FIX_FILL_FEED') == 'jetstream',
                    'FIX fill reports not on the durable stream: a missed message loses a fill (FR-FIX05, ADR-084)')
            # ADR-086: FIX sessions resume on any replica (FR-FIX11), and status requests have a read model
            # (FR-FIX04), so a reconnecting client can recover what it missed.
            if 'FIX_ACCEPTOR_PORT' in env:
                require(env.get('FIX_SESSION_STORE') == 'jetstream',
                        'FIX sessions restart at MsgSeqNum 1 on reconnect (FR-FIX11, ADR-086)')
                require((env.get('ORDER_READMODEL_URL') or '').startswith('http://trade-processor:'),
                        'FIX order status has no read model (FR-FIX04)')
                require(re.fullmatch(r'\d\d:\d\d:\d\d', env.get('FIX_SESSION_RESET_TIME') or '') and env.get('FIX_SESSION_TIME_ZONE'),
                        'FIX session reset time and zone not pinned (FR-GFS-08)')
            # ADR-095 (FR-FTL-01/03): the external CompID is on the TLS socket only, password-bound to named accounts,
            # and the keystore is the Secret's.
            if 'FIX_TLS_PORT' in env:
                tls_ids = (env.get('FIX_TLS_COMPIDS') or '').split(',')
                auth = env.get('FIX_SESSION_AUTH') or ''
                mounts = {m['mountPath'] for c in pod['containers'] for m in c.get('volumeMounts', [])}
                require(all(i and i not in (env.get('FIX_SESSION_COMPIDS') or '').split(',')
                            and re.search(rf'(^|,){i}:\$\(FIX_EXT_PASSWORD\):[\d*]', auth) for i in tls_ids),
                        'external FIX CompID not TLS-only with a Secret-backed password and an account binding (ADR-095)')
                require((env.get('FIX_SSL_KEYSTORE') or '').startswith('/etc/fix-tls/') and '/etc/fix-tls' in mounts
                        and any(v.get('secret', {}).get('secretName') == 'fix-tls' for v in pod.get('volumes', [])),
                        'FIX TLS keystore is not Secret fix-tls (ADR-095)')
        # FR-DRS-25: the production projection replays an outage from the stream, and the stream outlives NATS.
        if doc['metadata']['name'] == 'trade-processor' and pod:
            env = {e['name']: e.get('value') for e in pod['containers'][0].get('env', [])}
            require(env.get('PROJECTION_FEED_MODE') == 'jetstream',
                    'trade-processor on the core feed loses every side booked while it is down (FR-DRS-25)')
        if doc['kind'] == 'Deployment' and doc['metadata']['name'] == 'nats':
            data = next((v for v in pod['volumes'] if v['name'] == 'data'), {})
            require('persistentVolumeClaim' in data, 'NATS JetStream store is not on a PVC (FR-DRS-25)')
        # FR-GMR-04: a member launched through a shell (kept off PID 1 for the crash harness) must forward
        # SIGTERM to the JVM and exit with its status, or every delete/roll ends in SIGKILL after the grace.
        if doc['kind'] == 'StatefulSet':
            for c in (pod or {}).get('containers', []):
                if 'ClusterNodeMain' in ' '.join(c.get('command', [])):
                    script = c['command'][2] if c['command'][:2] == ['/bin/sh', '-c'] else ''
                    require(re.search(r"trap '[^']*kill -TERM \"\$pid\"[^']*' TERM", script) is not None
                            and 'exit "$status"' in script,
                            'member shell does not forward SIGTERM to the JVM (FR-GMR-04)')
        # FR-GMR-01 (ADR-087): a member's log outlives its pod. A /data volume deleted with the pod lets one
        # scale-down or two pod deletes destroy a majority of the logs; two empty members can then elect one of
        # themselves and commit on an empty history (proof-audit suspect #15).
        if doc['kind'] == 'StatefulSet' and doc['metadata']['name'] == 'order-matcher-cluster':
            claims = {t['metadata']['name'] for t in doc['spec'].get('volumeClaimTemplates', [])}
            retention = doc['spec'].get('persistentVolumeClaimRetentionPolicy', {})
            require('data' in claims and all(v['name'] != 'data' for v in pod.get('volumes', []))
                    and retention.get('whenScaled') == 'Retain' and retention.get('whenDeleted') == 'Retain',
                    'member /data is not a retained per-member claim (FR-GMR-01, ADR-087)')
        # ADR-096: each rig tier on its own pool (FR-PRG-05); gateways one per node, guaranteed whole CPUs for the
        # static CPU manager, counted by the rig, draining for longer than the LB deregisters them (FR-PRG-06/07).
        if pod and doc['metadata']['name'] in RIG_POOLS and doc['kind'] in ('Deployment', 'StatefulSet'):
            pool, taint = RIG_POOLS[doc['metadata']['name']]
            require(pod.get('nodeSelector') == {'cloud.google.com/gke-nodepool': pool}
                    and {'key': taint, 'operator': 'Equal', 'value': 'true', 'effect': 'NoSchedule'} in pod.get('tolerations', []),
                    f'{doc["metadata"]["name"]} is not on its own pool {pool} (FR-PRG-05)')
        if doc['kind'] == 'Deployment' and doc['metadata']['name'] == 'cluster-gateway':
            anti = pod.get('affinity', {}).get('podAntiAffinity', {}).get('requiredDuringSchedulingIgnoredDuringExecution', [])
            res = pod['containers'][0].get('resources', {})
            env = {e['name']: e.get('value') for e in pod['containers'][0].get('env', [])}
            delay = int(env.get('GATEWAY_DRAIN_DELAY_MS') or 0)
            require('replicas' not in doc['spec'], 'gateway count pinned in the pack; the rig owns it (FR-PRG-06)')
            require(any(t.get('topologyKey') == 'kubernetes.io/hostname'
                        and t.get('labelSelector', {}).get('matchLabels') == {'app': 'cluster-gateway'} for t in anti),
                    'two gateways may share a node (FR-PRG-06)')
            require(res.get('requests') == res.get('limits') and str(res.get('requests', {}).get('cpu', '')).isdigit(),
                    'gateway is not a guaranteed whole-CPU pod, so the static CPU manager will not pin it (FR-PRG-06)')
            require(delay >= 25000 and pod.get('terminationGracePeriodSeconds', 30) >= delay // 1000 + 15,
                    'gateway drain shorter than the LB deregistration, or longer than its grace period (FR-PRG-07)')
        if doc['kind'] == 'StatefulSet' and doc['metadata']['name'] == 'order-matcher-cluster':
            c = pod['containers'][0]
            jvm = next((e.get('value', '') for e in c.get('env', []) if e['name'] == 'JAVA_TOOL_OPTIONS'), '')
            xmx = re.search(r'-Xmx(\d+)g', jvm)
            limit = str(c.get('resources', {}).get('limits', {}).get('memory', ''))
            books = next((e.get('value') for e in c.get('env', []) if e['name'] == 'MAX_ACTIVE_BOOKS'), None)
            require('-XX:+UseZGC' in jvm and '-XX:+ZGenerational' in jvm and xmx
                    and limit.endswith('Gi') and int(xmx.group(1)) + 3 <= int(limit[:-2]),
                    'member is not on generational ZGC, or its heap leaves no room for direct memory and /dev/shm (FR-PRG-05)')
            purge = next((x for x in pod['containers'] if x['name'] == 'archive-purge'), None)
            if purge:   # FR-PRG-40: the gate holds it by this file, and history goes only by name
                penv = {e['name']: e.get('value') for e in purge.get('env', [])}
                require(penv.get('ARCHIVE_PURGE_HOLD_FILE') == ARCHIVE_PURGE_HOLD_FILE and purge['image'] == c['image']
                        and purge.get('resources', {}).get('requests') == purge.get('resources', {}).get('limits')
                        and (penv.get('ARCHIVE_PURGE_DISCARD_HISTORY') == 'true') != bool(penv.get('ARCHIVE_PURGE_OFFLOAD_URI')),
                        'archive-purge is not on the member image, not held by the gate\'s file, not Guaranteed, or '
                        'neither discards nor offloads history by name (FR-PRG-40)')
            require(books is not None and books.isdigit()
                    and int(xmx.group(1)) * 1024 >= int(books) * BOOK_MB_ZGC * 3 // 2,
                    'MAX_ACTIVE_BOOKS not pinned, or the heap cannot hold that many books under ZGC with headroom: '
                    'the derived cap would move with the heap and change BOOK_CAPACITY decisions (FR-PRG-05, ADR-079 §6)')
    text = json.dumps(docs)
    require('traderx-505400' not in text, 'old project/bucket reference survived')
    require('order-matcher-journal-gcs-hmac' not in text, 'disabled restore credential survived')
    validate_nats_memory(docs, require)
    validate_read_model(docs, require)
    validate_history_store(docs, require)


def prepare(output, tag, dns, published_path=None, public_path=None, population_path=None, exception_published=None,
            fix_test_clients=False, binary_port=None, no_bridges=False, throughput=False, read_model='rows', partitions=None,
            history_hot_days=0, purge=False):
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    generated = Path(os.environ.get('TRADERX_GENERATED_ROOT') or ROOT / 'generated') / 'code/target-generated'
    base = generated / 'kubernetes-runtime/manifests/base'
    gke = ROOT / 'specs/YU17-otc-rates/generation/kubernetes/cluster/gke'
    yu18 = ROOT / 'specs/YU18-risk-integration/generation/kubernetes/cluster/gke'
    docs = []
    inputs = []
    def rel(path):
        # The recorded input path is derivation identity: an empty-root render (TRADERX_GENERATED_ROOT
        # outside the checkout) must record the same 'generated/code/target-generated/...' string as
        # an in-repo one, or the two admit different candidates for the same source.
        return str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) \
            else str(Path('generated/code/target-generated') / path.relative_to(generated))
    def include(path):
        inputs.append({'path': rel(path),
                       'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        docs.extend(read(path))
    # Explicit inherited cluster manifests; generated runtime service overrides
    # compose afterward. Sandbox, backups and seeding jobs are separate scopes.
    # The members' /data is a retained per-member claim of class order-matcher-archive
    # (statefulset-emptydir.yaml, ADR-087); without the class the claims never bind and the members
    # stay Pending. It was applied by hand on traderx-demo (2026-10-26); rendered here since.
    for name in ['storageclass.yaml', 'statefulset-emptydir.yaml', 'gateway.yaml', 'nats.yaml',
                 'eod-price-db.yaml', 'eod-chain.yaml', 'risk-extract.yaml',
                 'reference-data.yaml', 'execution-algo-engine.yaml']:
        # A YU18 override wins (nats.yaml: JetStream on a PVC, the trade store's ledger).
        include(yu18 / name if (yu18 / name).exists() else gke / name)
    for svc in ['trade-processor', 'people-service', 'account-service', 'trade-service', 'edge-proxy']:
        for kind in ['deployment', 'service']:
            include(base / f'{svc}-{kind}.yaml')
    for svc in ['web-front-end-angular', 'api-explorer', 'tempo', 'prometheus', 'loki', 'grafana', 'otel-collector']:
        for kind in ['deployment','service']: include(base / f'{svc}-{kind}.yaml')
    for name in ['observability-otel', 'observability-tempo', 'observability-prometheus',
                 'observability-loki', 'observability-promtail', 'observability-grafana-datasources',
                 'observability-grafana-dashboard-providers','observability-grafana-dashboards']:
        include(base / f'{name}-configmap.yaml')
    include(base / 'edge-proxy-configmap.yaml')
    # Pod logs -> Loki. The collector has no logs pipeline, so without promtail Loki stays empty.
    for name in ['promtail-serviceaccount', 'promtail-clusterrole', 'promtail-clusterrolebinding',
                 'promtail-daemonset']:
        include(base / f'{name}.yaml')
    include(ROOT / 'specs/YU17-otc-rates/generation/kubernetes/frontend-gke/console.yaml')
    for name in ['clickhouse.yaml', 'history-feed.yaml']:   # FR-THT-02
        include(HISTORY_DIR / name)
    schema = read(base / 'database-init-configmap.yaml')
    inputs.append({'path':rel(base/'database-init-configmap.yaml'),
                   'sha256':hashlib.sha256((base/'database-init-configmap.yaml').read_bytes()).hexdigest()})
    # Place the database schema before all workloads in the output.
    docs = schema + docs
    docs = [d for d in docs if d['kind'] != 'Secret']
    aliases = [('database', 'eod-price-db', [('mysql',3306,3306)]),
               ('order-matcher', 'cluster-gateway', [('http',18110,18110),('fix',18130,18130)]),
               ('nats-broker', 'nats', [('client',4222,4222),('websocket',8081,8081)])]
    for name, app, ports in aliases:
        docs.append({'apiVersion':'v1','kind':'Service','metadata':{'name':name},
                     'spec':{'selector':{'app':app},'ports':[
                         {'name':n,'port':p,'targetPort':t} for n,p,t in ports]}})
    docs.append({'apiVersion':'v1','kind':'ServiceAccount','metadata':{'name':'taq-reader'}})
    for doc in docs:
        if doc['kind'] == 'StorageClass':
            doc['metadata'].pop('namespace', None)  # cluster-scoped and shared: one class, every namespace
            continue
        if doc['kind'] in ('ClusterRole', 'ClusterRoleBinding'):
            # Cluster-scoped: one per namespace, so a side namespace never rebinds the live one's.
            doc['metadata'].pop('namespace', None)
            doc['metadata']['name'] = f'promtail-{NAMESPACE}'
            if doc['kind'] == 'ClusterRoleBinding':
                doc['roleRef']['name'] = f'promtail-{NAMESPACE}'
                for s in doc.get('subjects', []): s['namespace'] = NAMESPACE
            continue
        doc['metadata']['namespace'] = NAMESPACE
        doc['metadata'].get('annotations', {}).pop('cloud.google.com/neg', None)
        if doc['kind'] == 'RoleBinding':
            for s in doc.get('subjects', []):
                if s['kind'] == 'ServiceAccount': s['namespace'] = NAMESPACE
        if doc['kind'] == 'Service':
            if doc['spec'].get('clusterIP') != 'None': doc['spec']['type'] = 'ClusterIP'
            doc['spec'].pop('externalTrafficPolicy', None)
            for port in doc['spec'].get('ports', []): port.pop('nodePort', None)
        pod = podspec(doc)
        if not pod: continue
        if doc['kind'] == 'DaemonSet':
            # Every node, engine nodes included: the members' logs are the ones worth shipping.
            pod['tolerations'] = [{'key':'traderx-engine','operator':'Exists','effect':'NoSchedule'}]
            for c in pod['containers']:
                c['resources'] = {'requests': {'cpu': '25m', 'memory': '64Mi'},
                                  'limits': {'cpu': '250m', 'memory': '128Mi'}}
            continue
        workload = doc['metadata']['name']
        pool, taint = RIG_POOLS.get(workload, ('default-pool', None))
        pod['nodeSelector'] = {'cloud.google.com/gke-nodepool': pool}
        if taint:
            pod['tolerations'] = [{'key':taint,'operator':'Equal','value':'true','effect':'NoSchedule'}]
        else:
            pod.pop('tolerations', None)
        if workload == 'order-matcher-cluster':
            pod['initContainers'] = [c for c in pod.get('initContainers', []) if c['name'] != 'restore-from-gcs']
        elif workload == 'cluster-gateway':
            doc['spec'].pop('replicas', None)   # ADR-096 §3: rig.sh, gateways.sh and the HPA own the count
        elif doc['kind'] == 'Deployment':
            doc['spec']['replicas'] = 1
        for container in pod.get('containers', []) + pod.get('initContainers', []):
            image = container['image']
            if image.startswith('traderx-api-explorer:'):
                container['image'] = REGISTRY + 'api-explorer:' + tag
            elif image.startswith('traderx/') or 'docker.pkg.dev/traderx-' in image:
                name = image.rsplit('/', 1)[-1].split(':')[0].split('@')[0]
                container['image'] = REGISTRY + name + ':' + tag
            env = container.setdefault('env', [])
            if doc['metadata']['name'] in ('cluster-gateway','web-front-end-console') and not any(e['name']=='RISK_CONTROL_TOKEN' for e in env):
                env.append({'name':'RISK_CONTROL_TOKEN','valueFrom':{'secretKeyRef':{'name':'auth-secrets','key':'risk-control-token'}}})
            for entry in env:
                if entry.get('name') in ('RISK_CONTROL_TOKEN',):
                    entry.pop('value', None)
                    entry['valueFrom'] = {'secretKeyRef':{'name':'auth-secrets','key':'risk-control-token'}}
            if doc['metadata']['name'] == 'web-front-end-console':
                for entry in env:
                    if entry['name'] in ('ADMIN_PASSWORD_HASH', 'AUTH_SESSION_SECRET'):
                        entry['valueFrom']['secretKeyRef'].pop('optional', None)
                env[:] = [e for e in env if e['name'] != 'KDB_Q_URL'] + [{'name': 'KDB_Q_URL', 'value': KDB_Q_URL}]
            if doc['metadata']['name'] == 'risk-extract':
                env.append({'name':'RISK_EXTRACT_READY_DIRECTORY','value':'/data/risk-extracts/ready'})
                if NAMESPACE != 'traderx':
                    # A side namespace never writes the demo's write-once extract bucket.
                    env[:] = [e for e in env if not e['name'].startswith('RISK_EXTRACT_GCS_HMAC')
                              and e['name'] != 'RISK_EXTRACT_SINK_URI']
                    env.append({'name':'RISK_EXTRACT_SINK_URI','value':'file:///data/risk-extracts'})
        if doc['metadata']['name'] == 'web-front-end-console':
            pod['serviceAccountName'] = 'web-front-end-console'
    for doc in docs:
        if doc['kind'] == 'PersistentVolumeClaim' and doc['metadata']['name'] == 'eod-price-db-data':
            # FR-PRG-37: 10Gi filled on 2026-11-04 after an 18.5M-order load and an epoch archive (InnoDB error 28).
            doc['spec']['resources']['requests']['storage'] = READ_MODEL_DB_CLAIM
    # Structured objects first, exact bucket substitutions second. No substring
    # image/project replacement masquerading as deployment identity validation.
    raw = yaml.safe_dump_all(docs, sort_keys=False)
    for old,new in BUCKETS.items(): raw = raw.replace(old,new)
    raw = re.sub(r'resolver [0-9.]+ ', f'resolver {dns} ', raw)
    raw = raw.replace('/var/log/pods/traderx_*', f'/var/log/pods/{NAMESPACE}_*')  # promtail scrape scope
    raw = raw.replace('.traderx.svc.', f'.{NAMESPACE}.svc.')  # member/ingress FQDNs follow the namespace
    docs = list(yaml.safe_load_all(raw))
    configure_private_runtime(docs)
    clients = load_clients(7 if fix_test_clients is True else int(fix_test_clients or 0)) if fix_test_clients else {}
    if clients:
        add_fix_test_clients(docs, clients)
    declared = population.load(population_path)
    refuse_sequenced_binary(docs, declared)
    if binary_port:
        add_binary_port(docs, binary_port)
    if no_bridges:
        without_bridges(docs)
    if throughput:
        throughput_members(docs)
    if read_model == 'snapshot':
        snapshot_read_model(docs, partitions or READ_MODEL_PARTITIONS)
    history_store(docs, history_hot_days)
    if purge:
        archive_purge(docs)
    apply_population(docs, declared, tag)
    assert bool(published_path) == bool(public_path), 'both private and public image locks required'
    if published_path:
        published = json.loads(published_path.read_text())
        for path in exception_published or []:   # each exception build's own inventory (build-images.py --exception)
            published['images'].update(json.loads(Path(path).read_text())['images'])
        pin_images(docs, published, json.loads(public_path.read_text()))
    validate(docs, declared)
    raw = yaml.safe_dump_all(docs, sort_keys=False)
    (output / 'candidate.yaml').write_text(raw)
    secrets = {}
    images = set()
    def visit(value):
        if isinstance(value, dict):
            if 'secretKeyRef' in value:
                ref = value['secretKeyRef']
                if not ref.get('optional'): secrets.setdefault(ref['name'],set()).add(ref['key'])
            for child in value.values(): visit(child)
        elif isinstance(value,list):
            for child in value: visit(child)
    requests = {'engine':{'cpuMilli':0,'memoryMi':0},'support':{'cpuMilli':0,'memoryMi':0}}
    unbounded=[]
    def cpu(v): return int(v[:-1]) if str(v).endswith('m') else int(float(v)*1000)
    def memory(v):
        v=str(v)
        return float(v[:-2])*1024 if v.endswith('Gi') else float(v[:-2]) if v.endswith('Mi') else float(v)/1048576
    for doc in docs:
        visit(doc)
        pod = podspec(doc)
        if not pod: continue
        side = 'engine' if doc['kind']=='StatefulSet' else 'support'
        for c in pod.get('containers',[])+pod.get('initContainers',[]): images.add(c['image'])
        for c in pod.get('containers',[]):
            req=c.get('resources',{}).get('requests',{})
            if not all(k in req for k in ('cpu','memory')):
                unbounded.append(f'{doc["metadata"]["name"]}/{c["name"]}')
            count=doc['spec'].get('replicas',1)
            requests[side]['cpuMilli'] += cpu(req.get('cpu','0'))*count
            requests[side]['memoryMi'] += memory(req.get('memory','0'))*count
    report = {'target':{'project':PROJECT,'cluster':'traderx-demo','zone':'us-east1-b','namespace':NAMESPACE},
              'state':'YU18-risk-integration','sourceCommit':subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip(),
              'rendererSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'scope':'cold synthetic demo candidate; no retained-state, financial or performance acceptance',
              'resources':len(docs),'images':sorted(images),'inputFiles':inputs,
              'requiredSecretKeys':{k:sorted(v) for k,v in secrets.items()}, 'declaredRequests':requests,
              'workloadsWithoutCompleteRequests':unbounded,
              'candidateSha256':hashlib.sha256(raw.encode()).hexdigest(),
              'deploymentReady':False,
              'resourceQualification':'provisional cold-demo startup envelopes; production/load qualification pending',
              'imageLocks':None if not published_path else {
                  'privateSha256':hashlib.sha256(published_path.read_bytes()).hexdigest(),
                  'publicSha256':hashlib.sha256(public_path.read_bytes()).hexdigest(),
                  'imageSourceCommit':json.loads(published_path.read_text())['sourceCommit'],
                  'exceptionPublishedSha256':[hashlib.sha256(Path(x).read_bytes()).hexdigest() for x in exception_published or []]},
              'population':population.load(population_path),
              'fixTestClients':clients or None,
              'remainingGates':['review per-image payload evidence and compatible admission',
                  'provision scoped storage credentials and private application secrets',
                  'verify node resource fit','server dry-run and live end-to-end proof']}
    (output/'plan.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--tag',required=True)
    p.add_argument('--dns-ip',required=True)
    p.add_argument('--published',type=Path)
    p.add_argument('--public-lock',type=Path)
    p.add_argument('--population',type=Path,help='the reviewed population (ADR-096 §6); default population.json')
    p.add_argument('--exception-published',type=Path,action='append',help="an exception build's published inventory")
    p.add_argument('--fix-test-clients',nargs='?',const=7,type=int,default=0,
                   help='N load CompIDs CLIENT2.. on the test accounts (default 7; FR-PRG-11)')
    p.add_argument('--binary-port',type=int,help='in-cluster binary entry for a throughput run (FR-PRG-13)')
    p.add_argument('--no-bridges',action='store_true',help="members' NATS bridges off for a throughput run (FR-PRG-14)")
    p.add_argument('--throughput-members',action='store_true',help="the bench's 450K member settings (FR-PRG-15)")
    p.add_argument('--read-model',choices=['rows','snapshot'],default='rows',
                   help='snapshot = the read model sized for high rates (FR-PRG-20). rows leaves trade-processor on its own'
                        ' default, which since FR-THT-01 is also snapshot (one partition, unsized): production passes snapshot')
    p.add_argument('--history-hot-days',type=int,default=0,
                   help='FR-THT-03: date partitions kept on ClickHouse\'s disk; older ones move to GCS (needs Secret history-cold-hmac); 0 = off')
    p.add_argument('--archive-purge',action='store_true',
                   help="purge each member's archive behind its second-latest snapshot, history discarded (FR-PRG-40)")
    p.add_argument('--partitions',type=int,help=f'read-model partitions with --read-model snapshot (default {READ_MODEL_PARTITIONS})')
    args=p.parse_args()
    import ipaddress
    ipaddress.ip_address(args.dns_ip)
    assert re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}',args.tag), 'invalid image tag'
    prepare(args.output.resolve(),args.tag,args.dns_ip,args.published,args.public_lock,args.population,args.exception_published,
            args.fix_test_clients,args.binary_port,args.no_bridges,args.throughput_members,args.read_model,args.partitions,
            args.history_hot_days,args.archive_purge)
