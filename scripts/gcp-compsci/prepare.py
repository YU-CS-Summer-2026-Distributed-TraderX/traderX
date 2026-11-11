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
BINARY_TEST_ACCOUNTS = [str(9900010 + n) for n in range(8)]   # FR-PRG-16: rig/binary-load.mjs, disjoint from the FIX set
# FR-PRG-43: the EOD extract aborts on a holding account with no counterparty (FR-RXT09), and the image carries this CSV
# and nothing else (issues/risk-extract-counterparties-configmap-shadows-the-image.md). The 2026-11-10 acceptance run
# would have failed its EOD stage on every load account; a render whose commit leaves one unmapped is refused.
COUNTERPARTIES = ROOT / 'specs/YU15-eod-risk-extract/reference-data/counterparties.csv'


def unmapped_test_accounts(csv=COUNTERPARTIES):
    mapped = {line.split(',')[0] for line in csv.read_text().splitlines()[1:] if line.strip()}
    return [a for a in FIX_TEST_ACCOUNTS + BINARY_TEST_ACCOUNTS if a not in mapped]


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
# ADR-103 (FR-PRG-05): members on c4d-standard-16. The hour at 464K orders/s (2026-11-10) ran 15 CPU / 40Gi.
MEMBER_CPU = '15'
MEMBER_MEMORY = '40Gi'
# FR-PRG-07: longer than the internal LB takes to mark a terminating gateway's node down (health check 8 s x 3).
GATEWAY_DRAIN_DELAY_MS = '30000'


def route_fills(c):
    """FR-FFR-14 (ADR-084 amendment): the gateway names itself to the cluster (GATEWAY_ID = its pod name, carried as the
    session's principal) and reads only its own fills subject, /fills.<GATEWAY_ID>, which the read model's fills router
    (FR-DRS-48) feeds from the order events of the orders this gateway submitted. Every gateway reading the whole order
    feed was 24 x ~400 MB/s at 450K orders/s (prod-450k-plan §5a)."""
    c['env'] = [e for e in c.get('env', []) if e['name'] not in ('GATEWAY_ID', 'FIX_FILL_FEED')]
    c['env'] += [{'name': 'GATEWAY_ID', 'valueFrom': {'fieldRef': {'fieldPath': 'metadata.name'}}},
                 {'name': 'FIX_FILL_FEED', 'value': 'routed'}]


MEMBER_SESSION_RING = '2048'   # ADR-104 (FR-XOD-24): EXACTLY_ONCE_SESSION_RING, at least the gateways' FIX_MAX_QUEUED_WRITES
GATEWAY_MAX_QUEUED_WRITES = 2048   # FixGatewayAcceptor's FIX_MAX_QUEUED_WRITES default


def exactly_once(c):
    """ADR-104 (yaakov 2026-11-10: "Turn on exactly-once", then the engine-side design): every FIX NewOrderSingle carries
    its session-day, MsgSeqNum and PossDup/PossResend to the engine, whose per-session ClOrdID ring answers a resend
    with its original and never books it twice (FR-XOD-19..22). Pipelined, nothing stored before submit. The session
    stream needs the ADR-086 store, which the gateway manifest already sets. Members must be format 15 first."""
    set_env(c, {'FIX_EXACTLY_ONCE': 'engine'})


def validate_routed_fills(docs, require):
    """FR-FFR-14: a routed fill feed needs the gateway's name from its pod and a jetstream read model to route from."""
    gw = next((d for d in docs if d['kind'] == 'Deployment' and d['metadata']['name'] == 'cluster-gateway'), None)
    if not gw:
        return
    env = {e['name']: e for e in podspec(gw)['containers'][0].get('env', [])}
    if env.get('FIX_FILL_FEED', {}).get('value') != 'routed':
        return
    require(env.get('GATEWAY_ID', {}).get('valueFrom', {}).get('fieldRef', {}).get('fieldPath') == 'metadata.name',
            'FIX_FILL_FEED=routed without GATEWAY_ID from the pod name: the gateway cannot name its fills subject (FR-FFR-14)')
    tp = read_model_workload(docs)
    tenv = {e['name']: e.get('value') for e in podspec(tp)['containers'][0].get('env', [])} if tp else {}
    require(tenv.get('PROJECTION_FEED_MODE') == 'jetstream' and tenv.get('FILLS_ROUTE', 'true') != '0',
            'FIX_FILL_FEED=routed needs the read model on the jetstream feed with its fills router on (FR-DRS-48)')


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
                # FR-PRG-05: generational ZGC (no G1 stop-the-world pauses), 15 CPUs for the DEDICATED driver's agents on a
                # c4d-standard-16 (ADR-103: the c4d-standard-8 leader held 400K at 6.6 of 7 cores), a 4 MiB Aeron receive
                # buffer (the engine pool's rmem_max allows it), and the book cap pinned. 40Gi: the archive's page cache
                # and the /dev/shm term buffers count against the cgroup; at 16Gi and 50-65 MB/s of log the members lived
                # in reclaim, and a stall past the heartbeat timeout was an election (RI34 §1b, 2026-11-10).
                c['resources'] = {'requests': {'cpu': MEMBER_CPU, 'memory': MEMBER_MEMORY}, 'limits': {'cpu': MEMBER_CPU, 'memory': MEMBER_MEMORY}}
                c['env'] = [e for e in c['env'] if e['name'] != 'MAX_ACTIVE_BOOKS']
                c['env'].append({'name': 'MAX_ACTIVE_BOOKS', 'value': MEMBER_MAX_ACTIVE_BOOKS})
                jvm = next(e for e in c['env'] if e['name'] == 'JAVA_TOOL_OPTIONS')
                kept = [t for t in jvm['value'].split() if not t.startswith(('-Xms', '-Xmx', '-XX:MaxGCPauseMillis', '-XX:+Use'))]
                jvm['value'] = ' '.join([MEMBER_JVM] + kept)
                set_env(c, {'EXACTLY_ONCE_SESSION_RING': MEMBER_SESSION_RING})
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
                # FR-FIX05 (ADR-084): FIX fill reports read a durable stream, so a fill this gateway misses is re-read
                # rather than lost. FR-FFR-14 (ADR-084 amendment): the stream is this gateway's own routed subject.
                route_fills(c)
                exactly_once(c)
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
# the ceiling). Measured (downstream-at-400k.md §7.12, +1 ms RTT): P=8 on the 2026-10 nats-pool shape (one c3-standard-8)
# held 125K orders/s with every hop persisting and the exactly-once accounting exact. ADR-103: on the three-server
# cluster with the batched wire (FR-DRS-41/42/43) the capture and the store held 464K for an hour (RI34 §1b, 2026-11-10);
# the stream is sized to that rate, split over the servers by placement (FR-PRG-41).
READ_MODEL_PARTITIONS = 8
READ_MODEL_RATE = 450_000            # orders/s the sizing below holds with every hop persisting
READ_MODEL_OUTAGE_MINUTES = 30       # the longest outage the stream must replay (a node replacement + a DB restart)
STREAM_BYTES_PER_ORDER = 462 + 588 * 3 // 2   # stored: one trade side + 1.5 order updates per order (§7.7)
READ_MODEL_POOLS = {'trade-processor': ('readmodel-pool', 'traderx-readmodel'), 'eod-price-db': ('db-pool', 'traderx-db')}
READ_MODEL_TP = {'cpu': '12', 'memory': '24Gi', 'jvm': '-XX:+UseZGC -XX:+ZGenerational -Xms16g -Xmx16g -XX:+ExitOnOutOfMemoryError'}
READ_MODEL_DB = {'cpu': '6', 'memory': '24Gi', 'bufferPool': '16G', 'disk': '200Gi'}
# NATS memory is the base manifest's for every profile (FR-PRG-28, validate_nats_memory), not this one's alone: the rows
# profile ran NATS at 2Gi and it was OOM-killed under the bridges' publish on 2026-10-30.
READ_MODEL_DB_CLASS = 'eod-price-db-fast'
READ_MODEL_DB_CLAIM = 'eod-price-db-fast'
READ_MODEL_DB_SIZE = '50Gi'   # FR-PRG-37; a claim only grows, so this never goes back down
READ_MODEL_DB_SNAPSHOT = 'eod-price-db-pre-fast'   # taken of eod-price-db-data while the database is at 0
# FR-PRG-42: the member archive grows ~125 B per order (rig.sh member-disk; the 2026-11-10 acceptance: 32Gi was ~4 min
# at 450K) and the production render runs no purge (FR-PRG-40 is opt-in). Three hours at rate: an acceptance hour, its
# EOD stage and a margin; a longer epoch at rate grows the claims (`rig.sh member-disk`) or renders the purge.
ARCHIVE_BYTES_PER_ORDER = 125
MEMBER_ARCHIVE_HOURS = 3
# FR-DRS-48: TRADERX_FILLS carries each order's updates once more, on its gateway's subject (1.5 × 588 B, the order part of
# STREAM_BYTES_PER_ORDER; ~890 B/order measured 2026-11-10), on the first server (FR-DRS-44's first tag). The window is
# what the gateway's ordered consumer may fall behind by and still find its next message: a gateway's drain (30 s) and
# restart, not a resume (the consumer starts from "new" under the pod's name; ADR-084 amendment). 2 GiB, the code's
# default, is the kind figure: ~5 s at 450K.
FILLS_BYTES_PER_ORDER = 588 * 3 // 2
FILLS_WINDOW_MINUTES = 3


def stream_retention_bytes(rate=READ_MODEL_RATE, minutes=READ_MODEL_OUTAGE_MINUTES):
    return rate * STREAM_BYTES_PER_ORDER * minutes * 60


def fills_retention_bytes(rate=READ_MODEL_RATE, minutes=FILLS_WINDOW_MINUTES):
    return rate * FILLS_BYTES_PER_ORDER * minutes * 60


def member_archive_gi(rate=READ_MODEL_RATE, hours=MEMBER_ARCHIVE_HOURS):
    """FR-PRG-42: the archive claim, rounded up to 50Gi (600Gi at 450K for three hours)."""
    return -(-rate * ARCHIVE_BYTES_PER_ORDER * hours * 3600 // (50 * 2**30)) * 50


def nats_servers(docs):
    return next(d for d in docs if d['kind'] == 'StatefulSet' and d['metadata']['name'] == 'nats')['spec']['replicas']


def store_per_server_gb(retention, partitions, servers, fills=0):
    """FR-PRG-41: one server's file store holds the partitions placed on it (ceil(P/N) of P, FR-DRS-44), plus 25% for
    the EOD streams and compaction, plus the fills stream (FR-DRS-48; one server holds it, every server is sized for
    it: one ConfigMap, one claim template)."""
    held = -(-partitions // servers)
    return -(-(retention * held // partitions + fills) * 5 // 4 // 10**9)


def placement(servers):
    """FR-DRS-44: the servers' tags, which are their pod names; partition p goes to tag p mod N."""
    return ','.join(f'nats-{i}' for i in range(servers))


def set_env(c, values):
    c['env'] = [e for e in c.get('env', []) if e['name'] not in values] + [{'name': k, 'value': v} for k, v in values.items()]


FIX_SESSION_BUCKETS_PER_SERVER = 4   # FR-GFS-21, GKE 2026-11-10: 1/3/12/24 buckets 140K/378K/653K/624K bodies/s
# FR-GFS-24 (slot B, 2026-11-11): 450K FIX is ~900K session-store bodies/s plus the capture's ~300 MB/s. Pinned, 4
# servers carried 995K + capture and 5 carried 1.26M + capture. 5 pending yaakov's choice of 4 or 5.
NATS_SERVERS = 5


def nats_server_count(docs, n):
    """FR-GFS-24: the NATS cluster at n servers. The StatefulSet's replicas, and the routes naming every server
    (FR-PRG-41's check), in the base's namespace and form."""
    nats = next(d for d in docs if d['kind'] == 'StatefulSet' and d['metadata']['name'] == 'nats')
    nats['spec']['replicas'] = n
    cm = next(d for d in docs if d['kind'] == 'ConfigMap' and d['metadata']['name'] == 'nats-server-config')
    conf = cm['data']['nats.conf']
    m = re.search(r'(?m)^(\s*)routes: \[\n(.*?)\n\s*\]', conf, re.S)
    first = re.search(r'nats://nats-0(\.nats-headless\.[^:]+):6222', m.group(2))
    indent = re.match(r'\s*', m.group(2)).group(0)
    routes = ',\n'.join(f'{indent}nats://nats-{i}{first.group(1)}:6222' for i in range(n))
    cm['data']['nats.conf'] = conf[:m.start(2)] + routes + conf[m.end(2):]


def nats_server_urls(docs):
    """FR-GFS-24: each server's own client URL, as the routes name it, in placement order."""
    conf = next(d for d in docs if d['kind'] == 'ConfigMap' and d['metadata']['name'] == 'nats-server-config')['data']['nats.conf']
    host = re.search(r'nats://nats-0(\.nats-headless\.[^:]+):6222', conf).group(1)
    return ','.join(f'nats://nats-{i}{host}:4222' for i in range(nats_servers(docs)))


def fix_session_buckets(docs):
    """FR-GFS-21: the gateways spread their FIX sessions over four buckets per NATS server, bucket i on server i mod N
    (as FR-DRS-44 places partitions). One FIX_SESSIONS stream on one server capped the 2026-11-10 acceptance run's
    FIX near 85K orders/s with ~2 s commits: a stream takes its fenced writes one at a time. FR-GFS-24: each bucket is
    written on a connection to its own server, never relayed by another."""
    servers = nats_servers(docs)
    gw = next(d for d in docs if d['kind'] == 'Deployment' and d['metadata']['name'] == 'cluster-gateway')
    set_env(podspec(gw)['containers'][0], {'FIX_SESSION_BUCKETS': str(FIX_SESSION_BUCKETS_PER_SERVER * servers),
                                                   'FIX_SESSION_PLACEMENT': placement(servers),
                                                   'FIX_SESSION_SERVER_URLS': nats_server_urls(docs)})


def snapshot_read_model(docs, partitions=READ_MODEL_PARTITIONS):
    """ADR-097 (FR-PRG-20): the members partition both bridges by account, trade-processor runs the snapshot read model
    on its own pool, NATS keeps the outage window, and the database moves to its own pool on a faster disk. The move
    is a copy, not a re-create: the new claim is restored from a VolumeSnapshot of the old one (taken at 0 replicas),
    so the read model the restore gate fingerprints is the one the old disk held."""
    retention = stream_retention_bytes()
    fills = fills_retention_bytes()
    servers = nats_servers(docs)
    store_gb = store_per_server_gb(retention, partitions, servers, fills)
    for doc in docs:
        name, kind = doc['metadata']['name'], doc['kind']
        pod = podspec(doc)
        if kind == 'StatefulSet' and name == 'order-matcher-cluster':
            set_env(pod['containers'][0], {'BRIDGE_PARTITIONS': str(partitions)})
        elif kind == 'Deployment' and name == 'trade-processor':
            c = pod['containers'][0]
            set_env(c, {'PROJECTION_FEED_MODE': 'jetstream', 'PROJECTION_FEED_PARTITIONS': str(partitions),
                        'PROJECTION_FEED_STREAM_LAYOUT': 'partition', 'PROJECTION_FEED_STREAM_PLACEMENT': placement(servers),
                        'TRADE_PROJECTION_MODE': 'snapshot', 'ORDER_FEED_PERSIST': 'false', 'ORDER_VIEW_SNAPSHOT_MS': '5000',
                        'PROJECTION_FEED_STREAM_MAX_BYTES': str(retention), 'PROJECTION_FEED_STREAM_MAX_AGE_HOURS': '2',
                        'FILLS_STREAM_MAX_BYTES': str(fills), 'FILLS_STREAM_MAX_AGE_MINUTES': str(FILLS_WINDOW_MINUTES)})
            jvm = next(e for e in c['env'] if e['name'] == 'JAVA_TOOL_OPTIONS')
            kept = [t for t in jvm['value'].split() if not t.startswith(('-Xms', '-Xmx', '-XX:MaxRAMPercentage', '-XX:+Use',
                                                                          '-XX:+ZGenerational', '-XX:+ExitOnOutOfMemoryError'))]
            jvm['value'] = ' '.join([READ_MODEL_TP['jvm']] + kept)
            c['resources'] = {'requests': {'cpu': READ_MODEL_TP['cpu'], 'memory': READ_MODEL_TP['memory']},
                              'limits': {'cpu': READ_MODEL_TP['cpu'], 'memory': READ_MODEL_TP['memory']}}
        elif kind == 'ConfigMap' and name == 'nats-server-config':
            doc['data']['nats.conf'] = re.sub(r'max_file_store: \S+', f'max_file_store: {store_gb}G', doc['data']['nats.conf'])
        elif kind == 'StatefulSet' and name == 'nats':
            claim = next(t for t in doc['spec']['volumeClaimTemplates'] if t['metadata']['name'] == 'data')
            claim['spec']['resources']['requests']['storage'] = f'{store_gb * 11 // 10 + 1}Gi'
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
# FR-THT-10: the partitioned render is the full-rate one (400K orders/s, ~1M feed messages/s). The feed costs ~170K rows
# per CPU-second (jnats ~320K msgs, the parser >1M; 2026-11-08), so ~6 cores at 1M/s, 8 to drain a backlog while it
# keeps up. The store took 1.5-3 cores draining 730K rows/s in the bake-off. Both on one c4d-standard-16 (HISTORY_MACHINE).
HISTORY_STORE_FULL_RATE = {'cpu': '6', 'memory': '24Gi', 'disk': '500Gi'}
HISTORY_FEED_FULL_RATE = {'cpu': '8', 'memory': '8Gi'}
HISTORY_CLASS = 'clickhouse-hot'
HISTORY_FEED_ENV = {'PROJECTION_FEED_STREAM': 'TRADERX_FEED', 'PROJECTION_FEED_PARTITIONS': '1',
                    'PROJECTION_FEED_STREAM_LAYOUT': 'one'}   # both services' defaults
# FR-THT-03 on GKE: GCS through its XML API with an HMAC key (Secret history-cold-hmac), one prefix per namespace.
HISTORY_COLD_BUCKET = f'{PROJECT}-traderx-history'
HISTORY_COLD_TIERED = '/etc/clickhouse-server/config.d/tiered.xml'
HISTORY_CLIENTS = ('history-feed', 'trade-processor', 'position-service')


def read_model_workload(docs):
    """trade-processor: one Deployment, or the StatefulSet shard_read_model renders for N replicas (FR-DRS-45); None if absent."""
    return next((d for d in docs if d['kind'] in ('Deployment', 'StatefulSet') and d['metadata']['name'] == 'trade-processor'), None)


READ_MODEL_REPLICAS_SERVICE = 'trade-processor-replicas'
# RI36 / ADR-103 §3: the production read model, two partitions per replica (P = 8). 12 cores each, as the one replica had
# (READ_MODEL_TP): at 400-520K orders/s one process read all 8 partitions at 7-10 cores with no headroom.
READ_MODEL_REPLICAS = 4
READ_MODEL_REPLICA_URL = 'http://{replica}.' + READ_MODEL_REPLICAS_SERVICE + ':18091'   # FR-DRS-46, {replica} = the pod name


def shard_read_model(docs, replicas=1):
    """FR-DRS-45: trade-processor as N replicas, each reading a disjoint block of the feed partitions. Each replica
    claims its partitions in SQL at start and refuses a partition another live replica holds, so a rollout must stop
    the old pod before the new one starts: one replica stays the Deployment, Recreate (under RollingUpdate the surge pod
    is refused and the rollout never completes). N > 1 is a StatefulSet, Parallel: the pod name is the replica id, the
    pod index picks the block (PROJECTION_FEED_PARTITIONS_OWNED=replica:<k>/<N>; P=8, N=2: 0-3 and 4-7), and the
    headless Service trade-processor-replicas names each pod for the order-view router. The trade-processor Service
    selects every replica: the order view's reads (/accounts/{id}/orders) need the router; a replica answers an account
    it does not own 421."""
    tp = read_model_workload(docs)
    if replicas <= 1:
        tp['spec']['strategy'] = {'type': 'Recreate'}
        return
    c = podspec(tp)['containers'][0]
    partitions = int({e['name']: e.get('value') for e in c.get('env', [])}.get('PROJECTION_FEED_PARTITIONS') or 1)
    assert replicas <= partitions, f'{replicas} read-model replicas over {partitions} partitions: a replica would own none'
    owned = ('TP_REPLICA_INDEX', 'PROJECTION_REPLICA_ID', 'PROJECTION_FEED_PARTITIONS_OWNED', 'PROJECTION_FEED_OWNED',
             'PROJECTION_REPLICA_URL')
    c['env'] = [e for e in c.get('env', []) if e['name'] not in owned] + [
        {'name': 'TP_REPLICA_INDEX', 'valueFrom': {'fieldRef': {'fieldPath': "metadata.labels['apps.kubernetes.io/pod-index']"}}},
        {'name': 'PROJECTION_REPLICA_ID', 'valueFrom': {'fieldRef': {'fieldPath': 'metadata.name'}}},
        {'name': 'PROJECTION_FEED_PARTITIONS_OWNED', 'value': f'replica:$(TP_REPLICA_INDEX)/{replicas}'},
        {'name': 'PROJECTION_REPLICA_URL', 'value': READ_MODEL_REPLICA_URL}]
    tp['kind'] = 'StatefulSet'
    for k in ('strategy', 'progressDeadlineSeconds', 'paused'):
        tp['spec'].pop(k, None)
    tp['spec'].update({'replicas': replicas, 'serviceName': READ_MODEL_REPLICAS_SERVICE, 'podManagementPolicy': 'Parallel'})
    meta = {'name': READ_MODEL_REPLICAS_SERVICE, 'labels': {'app': 'trade-processor'}}
    if 'namespace' in tp['metadata']:
        meta['namespace'] = tp['metadata']['namespace']
    docs.append({'apiVersion': 'v1', 'kind': 'Service', 'metadata': meta,
                 'spec': {'clusterIP': 'None', 'selector': {'app': 'trade-processor'},
                          'ports': [{'name': 'http', 'port': 18091, 'targetPort': 18091}]}})


def validate_read_model_replicas(docs, require):
    """FR-DRS-45: what shard_read_model renders, so a hand edit cannot run two owners or a stuck rollout."""
    tp = read_model_workload(docs)
    if tp is None:
        return
    env = {e['name']: e.get('value') for e in podspec(tp)['containers'][0].get('env', [])}
    if tp['kind'] == 'Deployment':
        require(tp['spec'].get('replicas', 1) == 1 and tp['spec'].get('strategy', {}).get('type') == 'Recreate',
                'trade-processor as a Deployment must be one Recreate replica: a second pod is refused its partitions (FR-DRS-45)')
        return
    n = tp['spec'].get('replicas')
    require(env.get('PROJECTION_FEED_PARTITIONS_OWNED') == f'replica:$(TP_REPLICA_INDEX)/{n}'
            and tp['spec'].get('serviceName') == READ_MODEL_REPLICAS_SERVICE
            and env.get('PROJECTION_REPLICA_URL') == READ_MODEL_REPLICA_URL,
            f'trade-processor\'s {n} replicas must each own replica:<pod index>/{n} of the partitions and reach each other at {READ_MODEL_REPLICA_URL} (FR-DRS-45/46)')


def history_store(docs, hot_days=0):
    """FR-THT-02: ClickHouse and history-feed sized and guaranteed, the store on its own retained class, history-feed
    on exactly trade-processor's streams. FR-THT-03 with hot_days > 0: the `tiered` policy, cold volume on GCS."""
    tp = read_model_workload(docs)
    feed = {e['name']: e.get('value') for e in podspec(tp)['containers'][0].get('env', [])}
    full = int(feed.get('PROJECTION_FEED_PARTITIONS') or 1) > 1
    store_size, feed_size = (HISTORY_STORE_FULL_RATE, HISTORY_FEED_FULL_RATE) if full else (HISTORY_STORE, HISTORY_FEED)
    for doc in docs:
        name, pod = doc['metadata']['name'], podspec(doc)
        if doc['kind'] == 'StatefulSet' and name == 'clickhouse':
            c = pod['containers'][0]
            c['resources'] = {'requests': {'cpu': store_size['cpu'], 'memory': store_size['memory']},
                              'limits': {'cpu': store_size['cpu'], 'memory': store_size['memory']}}
            claim = doc['spec']['volumeClaimTemplates'][0]['spec']
            claim['storageClassName'] = HISTORY_CLASS
            claim['resources']['requests']['storage'] = store_size['disk']
            if hot_days > 0:
                set_env(c, {'HISTORY_COLD_ENDPOINT': f'https://storage.googleapis.com/{HISTORY_COLD_BUCKET}/{NAMESPACE}/'})
                secret_env(c, 'HISTORY_COLD_KEY_ID', 'history-cold-hmac', 'access-id')
                secret_env(c, 'HISTORY_COLD_SECRET', 'history-cold-hmac', 'secret')
                c.setdefault('volumeMounts', []).append({'name': 'tiered', 'mountPath': HISTORY_COLD_TIERED, 'subPath': 'tiered.xml'})
                pod.setdefault('volumes', []).append({'name': 'tiered', 'configMap': {'name': 'clickhouse-tiered'}})
        elif doc['kind'] == 'Deployment' and name == 'history-feed':
            c = pod['containers'][0]
            c['resources'] = {'requests': {'cpu': feed_size['cpu'], 'memory': feed_size['memory']},
                              'limits': {'cpu': feed_size['cpu'], 'memory': feed_size['memory']}}
            set_env(c, {k: feed.get(k) or v for k, v in HISTORY_FEED_ENV.items()} | {'HISTORY_HOT_DAYS': str(hot_days)})
        elif doc['kind'] == 'Deployment' and name == 'position-service' and feed.get('SETTLEMENT_T_PLUS_DAYS'):
            # ADR-102 cutover: the blotter derives a history row's state with trade-processor's own T+N.
            set_env(pod['containers'][0], {'SETTLEMENT_T_PLUS_DAYS': feed['SETTLEMENT_T_PLUS_DAYS']})
    if hot_days > 0:
        docs.append({'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'clickhouse-tiered', 'namespace': NAMESPACE},
                     'data': {'tiered.xml': (HISTORY_DIR / 'clickhouse-tiered.xml').read_text()}})
    docs.append({'apiVersion': 'storage.k8s.io/v1', 'kind': 'StorageClass', 'metadata': {'name': HISTORY_CLASS},
                 'provisioner': 'pd.csi.storage.gke.io',
                 'parameters': {'type': 'hyperdisk-balanced', 'csi.storage.k8s.io/fstype': 'ext4'},
                 'volumeBindingMode': 'WaitForFirstConsumer', 'reclaimPolicy': 'Retain', 'allowVolumeExpansion': True})
    # The store has no user setup (in-cluster only): only its writer, trade-processor (the rig's G3h reads through it;
    # a forced settlement reads a side) and position-service (the blotter, ADR-102 cutover) reach the port.
    docs.append({'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
                 'metadata': {'name': 'clickhouse-clients', 'namespace': NAMESPACE},
                 'spec': {'podSelector': {'matchLabels': {'app': 'clickhouse'}}, 'policyTypes': ['Ingress'],
                          'ingress': [{'from': [{'podSelector': {'matchLabels': {'app': a}}} for a in HISTORY_CLIENTS],
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
    tp = read_model_workload(docs)
    env = lambda d: {e['name']: e.get('value') for e in podspec(d)['containers'][0].get('env', [])}
    want = {k: env(tp).get(k) or v for k, v in HISTORY_FEED_ENV.items()}
    require({k: env(writer).get(k) or v for k, v in HISTORY_FEED_ENV.items()} == want,
            "history-feed does not read trade-processor's streams and partitions (ADR-098 §2)")
    # ADR-102 cutover: the blotter reads the store, with trade-processor's settlement rule.
    pol = next((d for d in docs if d['kind'] == 'NetworkPolicy' and d['metadata']['name'] == 'clickhouse-clients'), None)
    admitted = {f['podSelector']['matchLabels']['app'] for f in (pol or {}).get('spec', {}).get('ingress', [{}])[0].get('from', [])}
    require(set(HISTORY_CLIENTS) <= admitted, f'clickhouse-clients does not admit {sorted(set(HISTORY_CLIENTS) - admitted)} (ADR-102)')
    ps = next((d for d in docs if d['kind'] == 'Deployment' and d['metadata']['name'] == 'position-service'), None)
    require(ps is None or (env(ps).get('SETTLEMENT_T_PLUS_DAYS') or '1') == (env(tp).get('SETTLEMENT_T_PLUS_DAYS') or '1'),
            "position-service's SETTLEMENT_T_PLUS_DAYS differs from trade-processor's: the blotter would show another settlement (ADR-102)")
    if int(env(writer).get('HISTORY_HOT_DAYS') or 0) > 0:
        c = podspec(store)['containers'][0]
        names = {e['name'] for e in c.get('env', [])}
        require({'HISTORY_COLD_ENDPOINT', 'HISTORY_COLD_KEY_ID', 'HISTORY_COLD_SECRET'} <= names
                and any(m['mountPath'] == HISTORY_COLD_TIERED for m in c.get('volumeMounts', [])),
                'HISTORY_HOT_DAYS > 0 without the tiered policy and its cold disk on ClickHouse (FR-THT-03)')


# FR-PRG-28: NATS's memory, as the pod and NATS itself each see it. A server runs alone on one of nats-pool's
# c4d-standard-16 (ADR-103): 62 GiB, GKE allocatable ~56 GiB (its reservation: 25% of the first 4 GiB, 20% of the next 4,
# 10% of the next 8, 6% above, plus 100 MiB eviction), less ~2 GiB kept for the node's DaemonSets.
NATS_NODE_BUDGET = 54 * 2**30


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
    nats = next(d for d in docs if d['kind'] == 'StatefulSet' and d['metadata']['name'] == 'nats')
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


def validate_nats_cluster(docs, require):
    """FR-PRG-41 (ADR-103): three servers in one cluster, one per node, each on its own retained Hyperdisk claim, the
    routes naming every server, and each server named and tagged by its pod, so a capture stream placed by tag
    (PROJECTION_FEED_STREAM_PLACEMENT, FR-DRS-44) lands on a server that exists and the partitions spread over all of
    them. One server stored ~0.4-0.5M msgs/s whatever the stream count (FR-DRS-34) and one volume saturated at 400K
    orders/s (RI34 §1b); fewer than three cannot keep the JetStream meta group's majority through a loss."""
    nats = next(d for d in docs if d['kind'] == 'StatefulSet' and d['metadata']['name'] == 'nats')
    n = nats['spec']['replicas']
    pod = podspec(nats)
    conf = next(d for d in docs if d['kind'] == 'ConfigMap' and d['metadata']['name'] == 'nats-server-config')['data']['nats.conf']
    require(n >= 3, 'NATS: fewer than three servers cannot keep a JetStream majority through a server loss (FR-PRG-41)')
    routes = re.findall(r'nats://(nats-\d+)\.nats-headless\.', conf)
    require(sorted(routes) == [f'nats-{i}' for i in range(n)] and nats['spec'].get('serviceName') == 'nats-headless',
            'NATS: the routes must name every server of the StatefulSet through nats-headless (FR-PRG-41)')
    require(re.search(r'^\s*server_name:\s*POD_NAME\s*$', conf, re.M) is not None
            and re.search(r'^\s*server_tags:\s*\["POD_NAME"\]\s*$', conf, re.M) is not None
            and 'POD_NAME' in ' '.join(pod['containers'][0].get('args', [])),
            'NATS: a server must be named and tagged by its pod, substituted at start (FR-PRG-41)')
    anti = pod.get('affinity', {}).get('podAntiAffinity', {}).get('requiredDuringSchedulingIgnoredDuringExecution', [])
    require(any(t.get('topologyKey') == 'kubernetes.io/hostname' and t.get('labelSelector', {}).get('matchLabels') == {'app': 'nats'}
                for t in anti), 'NATS: servers must be one per node (FR-PRG-41)')
    claims = {t['metadata']['name']: t for t in nats['spec'].get('volumeClaimTemplates', [])}
    retention = nats['spec'].get('persistentVolumeClaimRetentionPolicy', {})
    require('data' in claims and all(v['name'] != 'data' for v in pod.get('volumes', []))
            and retention.get('whenScaled') == 'Retain' and retention.get('whenDeleted') == 'Retain',
            'NATS: /data is not a retained per-server claim (FR-PRG-41, FR-DRS-25)')
    sc_name = claims.get('data', {}).get('spec', {}).get('storageClassName')
    sc = next((d for d in docs if d['kind'] == 'StorageClass' and d['metadata']['name'] == sc_name), None)
    require(sc is not None and sc.get('parameters', {}).get('type') == 'hyperdisk-balanced',
            "NATS: a server's claim is not on a rendered Hyperdisk Balanced class (FR-PRG-41)")
    gw = next((d for d in docs if d['kind'] == 'Deployment' and d['metadata']['name'] == 'cluster-gateway'), None)
    gw_env = {e['name']: e.get('value') for e in podspec(gw)['containers'][0].get('env', [])} if gw else {}
    if gw_env.get('FIX_SESSION_STORE') == 'jetstream':
        host = re.search(r'nats://nats-0(\.nats-headless\.[^:]+):6222', conf)
        require(gw_env.get('FIX_SESSION_PLACEMENT') == placement(n)
                and gw_env.get('FIX_SESSION_SERVER_URLS') == ','.join(f'nats://nats-{i}{host.group(1) if host else ""}:4222' for i in range(n))
                and int(gw_env.get('FIX_SESSION_BUCKETS') or 0) % n == 0 and int(gw_env.get('FIX_SESSION_BUCKETS') or 0) >= n,
                "FIX session store: each bucket must be placed on, and written to, its own server: placement, server URLs "
                "(one per server, in order) and a bucket count that is a multiple of the servers (FR-GFS-21, FR-GFS-24)")
    tp = read_model_workload(docs)
    env = {e['name']: e.get('value') for e in podspec(tp)['containers'][0].get('env', [])} if tp else {}
    if env.get('PROJECTION_FEED_STREAM_LAYOUT') == 'partition':
        require(sorted((env.get('PROJECTION_FEED_STREAM_PLACEMENT') or '').split(',')) == [f'nats-{i}' for i in range(n)],
                'NATS: the partitioned capture must be placed over every server by tag (FR-PRG-41, FR-DRS-44)')


def validate_read_model(docs, require):
    """FR-PRG-20 preconditions: the parts of the snapshot read model only work together."""
    def env(name, kind='Deployment'):
        d = next((d for d in docs if d['kind'] == kind and d['metadata']['name'] == name), None)
        return {e['name']: e.get('value') for e in podspec(d)['containers'][0].get('env', [])} if d else {}
    tp, members = env('trade-processor', (read_model_workload(docs) or {}).get('kind')), env('order-matcher-cluster', 'StatefulSet')
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
    nats = next(d for d in docs if d['kind'] == 'StatefulSet' and d['metadata']['name'] == 'nats')
    claim = next(t for t in nats['spec']['volumeClaimTemplates'] if t['metadata']['name'] == 'data')
    # FR-PRG-41: the limit is the whole feed's; one server holds ceil(P/N) of the P partitions (FR-DRS-44).
    partitions, servers = int(tp.get('PROJECTION_FEED_PARTITIONS') or 1), nats['spec']['replicas']
    held = int(tp.get('PROJECTION_FEED_STREAM_MAX_BYTES') or 0) * (-(-partitions // servers)) // partitions
    require(held < store and int(claim['spec']['resources']['requests']['storage'][:-2]) * 2**30 > store,
            "a server's share of the stream limit must sit under its max_file_store, and max_file_store under its claim (FR-PRG-20, FR-PRG-41)")
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
            require(env.get('FIX_FILL_FEED') in ('jetstream', 'routed'),
                    'FIX fill reports not on a durable stream: a missed message loses a fill (FR-FIX05, ADR-084)')
            # ADR-086: FIX sessions resume on any replica (FR-FIX11), and status requests have a read model
            # (FR-FIX04), so a reconnecting client can recover what it missed.
            if 'FIX_ACCEPTOR_PORT' in env:
                require(env.get('FIX_SESSION_STORE') == 'jetstream',
                        'FIX sessions restart at MsgSeqNum 1 on reconnect (FR-FIX11, ADR-086)')
                require(env.get('FIX_EXACTLY_ONCE') == 'engine',
                        'FIX orders not exactly-once in the engine: a resend past its key window books twice, or each order'
                        ' waits on a store write (FR-PRG-46, ADR-104)')
                ring = next((e.get('value') for d in docs if d['kind'] == 'StatefulSet'
                             and d['metadata']['name'] == 'order-matcher-cluster'
                             for e in podspec(d)['containers'][0].get('env', [])
                             if e['name'] == 'EXACTLY_ONCE_SESSION_RING'), None)
                require(int(ring or 2048) >= int(env.get('FIX_MAX_QUEUED_WRITES') or GATEWAY_MAX_QUEUED_WRITES),
                        'members\' EXACTLY_ONCE_SESSION_RING is below the gateways\' FIX_MAX_QUEUED_WRITES: a resend of what'
                        ' one session can have outstanding would be refused AGED_OUT (FR-XOD-24, ADR-104)')
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
        if doc['kind'] == 'StatefulSet' and doc['metadata']['name'] == 'nats':
            require(any(t['metadata']['name'] == 'data' for t in doc['spec'].get('volumeClaimTemplates', [])),
                    'NATS JetStream store is not on a PVC (FR-DRS-25)')
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
            size = str(next((t for t in doc['spec'].get('volumeClaimTemplates', []) if t['metadata']['name'] == 'data'), {})
                       .get('spec', {}).get('resources', {}).get('requests', {}).get('storage', ''))
            require(size.endswith('Gi') and int(size[:-2]) >= member_archive_gi(),
                    f'member archive claim {size or "unset"} holds less than {MEMBER_ARCHIVE_HOURS} h at '
                    f'{READ_MODEL_RATE} orders/s (FR-PRG-42)')
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
    # FR-PRG-09 (ADR-103 amendment, option A, yaakov 2026-11-10): the gateway count is fixed and sized to sessions; an
    # HPA cannot place a session that connected before it scaled.
    require(not any(d['kind'] == 'HorizontalPodAutoscaler' and d['spec'].get('scaleTargetRef', {}).get('name') == 'cluster-gateway'
                    for d in docs), 'an HPA on cluster-gateway: the standing shape is a fixed count sized to sessions (FR-PRG-09)')
    text = json.dumps(docs)
    require('traderx-505400' not in text, 'old project/bucket reference survived')
    require('order-matcher-journal-gcs-hmac' not in text, 'disabled restore credential survived')
    validate_nats_memory(docs, require)
    validate_nats_cluster(docs, require)
    validate_routed_fills(docs, require)
    validate_read_model(docs, require)
    validate_read_model_replicas(docs, require)
    validate_history_store(docs, require)


def prepare(output, tag, dns, published_path=None, public_path=None, population_path=None, exception_published=None,
            fix_test_clients=False, binary_port=None, no_bridges=False, throughput=False, read_model='rows', partitions=None,
            history_hot_days=0, purge=False, read_model_replicas=None, nats_servers_count=NATS_SERVERS):
    unmapped = unmapped_test_accounts()
    assert not unmapped, f'load test accounts with no counterparty in {COUNTERPARTIES.name}: {unmapped} (FR-PRG-43)'
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
            doc['spec']['resources']['requests']['storage'] = READ_MODEL_DB_SIZE
        if doc['kind'] == 'StatefulSet' and doc['metadata']['name'] == 'order-matcher-cluster':
            # FR-PRG-42: the archive claim holds MEMBER_ARCHIVE_HOURS at rate; the pack's 32Gi is the kind/demo figure.
            claim = next(t for t in doc['spec']['volumeClaimTemplates'] if t['metadata']['name'] == 'data')
            claim['spec']['resources']['requests']['storage'] = f'{member_archive_gi()}Gi'
    # Structured objects first, exact bucket substitutions second. No substring
    # image/project replacement masquerading as deployment identity validation.
    raw = yaml.safe_dump_all(docs, sort_keys=False)
    for old,new in BUCKETS.items(): raw = raw.replace(old,new)
    raw = re.sub(r'resolver [0-9.]+ ', f'resolver {dns} ', raw)
    raw = raw.replace('/var/log/pods/traderx_*', f'/var/log/pods/{NAMESPACE}_*')  # promtail scrape scope
    raw = raw.replace('.traderx.svc.', f'.{NAMESPACE}.svc.')  # member/ingress FQDNs follow the namespace
    docs = list(yaml.safe_load_all(raw))
    configure_private_runtime(docs)
    nats_server_count(docs, nats_servers_count)
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
    fix_session_buckets(docs)
    history_store(docs, history_hot_days)
    shard_read_model(docs, read_model_replicas or (min(READ_MODEL_REPLICAS, partitions or READ_MODEL_PARTITIONS)
                                                   if read_model == 'snapshot' else 1))
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
    p.add_argument('--read-model-replicas',type=int,
                   help=f'FR-DRS-45: trade-processor replicas, each owning a block of the partitions (a StatefulSet above 1;'
                        f' default {READ_MODEL_REPLICAS} with --read-model snapshot, else 1)')
    args=p.parse_args()
    import ipaddress
    ipaddress.ip_address(args.dns_ip)
    assert re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}',args.tag), 'invalid image tag'
    prepare(args.output.resolve(),args.tag,args.dns_ip,args.published,args.public_lock,args.population,args.exception_published,
            args.fix_test_clients,args.binary_port,args.no_bridges,args.throughput_members,args.read_model,args.partitions,
            args.history_hot_days,args.archive_purge,args.read_model_replicas)
