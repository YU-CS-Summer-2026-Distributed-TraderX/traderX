"""kind-yu18-rig.py — render a disposable kind rig for the yu18-* proofs, in production's read-model shape.

The YU17 kind cluster tier (members, gateway, NATS JetStream, MariaDB, trade-processor) with what production
adds on top, taken from prepare.py's own functions rather than restated: the JetStream projection and fill feed
(configure_private_runtime, minus the TLS socket), and ADR-097's snapshot read model on per-partition streams
(snapshot_read_model's environment, minus its GKE pools and disks). Plus what the FIX proofs need: CLIENT1 bound to
auth-secrets/fix-session-password, ADR-086 sessions in JetStream, status from the order read model.

    python3 scripts/proofs/kind-yu18-rig.py --tag gap18 [--partitions 4] [--gateways 1] | kubectl --context kind-<c> apply -f -

Images: traderx/cluster-node:<tag> (build-cluster-image.sh) and traderx/trade-processor:<tag>
(build-service-images.sh), loaded into the kind nodes. Secrets are random per render: nothing to leak, and
kind's dev literals are refused by FR-ERH-S5 anyway (ALLOW_DEV_SECRETS stays true for the one-off proof pods only
through the manifests' own value).
"""
import argparse, importlib.util, pathlib, secrets, sys, yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
K = ROOT / 'specs/YU17-otc-rates/generation/kubernetes/cluster'
spec = importlib.util.spec_from_file_location('prepare', ROOT / 'scripts/gcp-compsci/prepare.py')
prep = importlib.util.module_from_spec(spec); spec.loader.exec_module(prep)


def render(tag, partitions, gateways, ns='traderx'):
    docs = []
    for f in ['statefulset.yaml', 'nats.yaml', 'eod-price-db.yaml', 'trade-processor.yaml', 'gateway.yaml']:
        docs += [d for d in yaml.safe_load_all((K / f).read_text()) if d]
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
            if c['image'].startswith(('traderx/cluster-node:', 'traderx/trade-processor:')):
                c['image'] = c['image'].split(':')[0] + ':' + tag
                c['imagePullPolicy'] = 'IfNotPresent'
            c['env'] = [e for e in c.get('env', []) if e['name'] not in ('OTEL_TRACES',)]
        name = d['metadata']['name']
        if name == 'cluster-gateway' and d['kind'] == 'Deployment':
            d['spec']['replicas'] = gateways
            c = pod['containers'][0]
            prep.set_env(c, {'FIX_SESSION_STORE': 'jetstream', 'ORDER_READMODEL_URL': 'http://trade-processor:18091'})
            c['env'] = [e for e in c['env'] if e['name'] not in ('FIX_SESSION_PASSWORD', 'FIX_SESSION_AUTH', 'RISK_CONTROL_TOKEN')]
            c['env'] += [{'name': 'FIX_SESSION_PASSWORD', 'valueFrom': {'secretKeyRef': {'name': 'auth-secrets', 'key': 'fix-session-password'}}},
                         {'name': 'FIX_SESSION_AUTH', 'value': 'CLIENT1:$(FIX_SESSION_PASSWORD):*'},
                         {'name': 'RISK_CONTROL_TOKEN', 'valueFrom': {'secretKeyRef': {'name': 'auth-secrets', 'key': 'risk-control-token'}}}]
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
    return docs


if __name__ == '__main__':
    a = argparse.ArgumentParser()
    a.add_argument('--tag', required=True)
    a.add_argument('--partitions', type=int, default=4)
    a.add_argument('--gateways', type=int, default=1)
    o = a.parse_args()
    yaml.safe_dump_all(render(o.tag, o.partitions, o.gateways), sys.stdout, sort_keys=False)
