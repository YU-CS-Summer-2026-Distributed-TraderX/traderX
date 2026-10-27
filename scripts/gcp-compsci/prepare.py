#!/usr/bin/env python3
"""Render a private CompSci cold-demo candidate; never contact or apply to a cluster."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import yaml

ROOT = Path(__file__).resolve().parents[2]
PROJECT = 'project-compsci-484414'
REGISTRY = f'us-east1-docker.pkg.dev/{PROJECT}/traderx-demo/'
NAMESPACE = os.environ.get('TRADERX_NAMESPACE') or 'traderx'  # ponytail: a side namespace for a proof next to the live demo
CUSTOM_WORKLOADS = {'order-matcher-cluster','cluster-gateway','risk-extract',
                    'people-service','account-service','trade-service','trade-processor',
                    'position-service','price-publisher','reference-data','execution-algo-engine',
                    'web-front-end-angular','web-front-end-console','api-explorer'}
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
        for c in pod.get('containers', []):
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


def validate(docs):
    def require(value,message):
        if not value: raise AssertionError(message)
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
                        'FIX_SESSION_PASSWORD'):
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
    text = json.dumps(docs)
    require('traderx-505400' not in text, 'old project/bucket reference survived')
    require('order-matcher-journal-gcs-hmac' not in text, 'disabled restore credential survived')


def prepare(output, tag, dns, published_path=None, public_path=None):
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
    # The members' /data is a generic ephemeral volume of class order-matcher-archive
    # (statefulset-emptydir.yaml); without the class the claims never bind and the members stay
    # Pending. It was applied by hand on traderx-demo (2026-10-26); rendered here since.
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
        engine = doc['metadata']['name'] == 'order-matcher-cluster'
        pod['nodeSelector'] = {'cloud.google.com/gke-nodepool': 'engine-pool' if engine else 'default-pool'}
        if engine:
            pod['initContainers'] = [c for c in pod.get('initContainers', []) if c['name'] != 'restore-from-gcs']
            pod['tolerations'] = [{'key':'traderx-engine','operator':'Equal','value':'true','effect':'NoSchedule'}]
        else:
            pod.pop('tolerations', None)
            if doc['kind'] == 'Deployment': doc['spec']['replicas'] = 1
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
            if doc['metadata']['name'] == 'risk-extract':
                env.append({'name':'RISK_EXTRACT_READY_DIRECTORY','value':'/data/risk-extracts/ready'})
                if NAMESPACE != 'traderx':
                    # A side namespace never writes the demo's write-once extract bucket.
                    env[:] = [e for e in env if not e['name'].startswith('RISK_EXTRACT_GCS_HMAC')
                              and e['name'] != 'RISK_EXTRACT_SINK_URI']
                    env.append({'name':'RISK_EXTRACT_SINK_URI','value':'file:///data/risk-extracts'})
        if doc['metadata']['name'] == 'web-front-end-console':
            pod['serviceAccountName'] = 'web-front-end-console'
    # Structured objects first, exact bucket substitutions second. No substring
    # image/project replacement masquerading as deployment identity validation.
    raw = yaml.safe_dump_all(docs, sort_keys=False)
    for old,new in BUCKETS.items(): raw = raw.replace(old,new)
    raw = re.sub(r'resolver [0-9.]+ ', f'resolver {dns} ', raw)
    raw = raw.replace('/var/log/pods/traderx_*', f'/var/log/pods/{NAMESPACE}_*')  # promtail scrape scope
    raw = raw.replace('.traderx.svc.', f'.{NAMESPACE}.svc.')  # member/ingress FQDNs follow the namespace
    docs = list(yaml.safe_load_all(raw))
    configure_private_runtime(docs)
    assert bool(published_path) == bool(public_path), 'both private and public image locks required'
    if published_path:
        pin_images(docs, json.loads(published_path.read_text()), json.loads(public_path.read_text()))
    validate(docs)
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
                  'imageSourceCommit':json.loads(published_path.read_text())['sourceCommit']},
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
    args=p.parse_args()
    import ipaddress
    ipaddress.ip_address(args.dns_ip)
    assert re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}',args.tag), 'invalid image tag'
    prepare(args.output.resolve(),args.tag,args.dns_ip,args.published,args.public_lock)
