#!/usr/bin/env python3
"""Render a private CompSci cold-demo candidate; never contact or apply to a cluster."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import yaml

ROOT = Path(__file__).resolve().parents[2]
PROJECT = 'project-compsci-484414'
REGISTRY = f'us-east1-docker.pkg.dev/{PROJECT}/traderx-demo/'
NAMESPACE = 'traderx'
BUCKETS = {
    'traderx-505400-risk-extracts': f'{PROJECT}-traderx-risk-extracts',
    'traderx-505400-order-matcher-journal-archive': f'{PROJECT}-traderx-journal-archive',
    'traderx-taq-raw': f'{PROJECT}-traderx-taq-raw',
}


def read(path):
    return [d for d in yaml.safe_load_all(path.read_text()) if d]


def podspec(doc):
    if doc['kind'] in ('Deployment', 'StatefulSet', 'Job'):
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
                require('traderx-505400' not in image and not image.startswith('traderx/'), 'old/local image')
                if 'docker.pkg.dev' in image:
                    require(image.startswith(REGISTRY), 'wrong project/repository')
                require(':latest' not in image, 'floating latest image')
    text = json.dumps(docs)
    require('traderx-505400' not in text, 'old project/bucket reference survived')
    require('order-matcher-journal-gcs-hmac' not in text, 'disabled restore credential survived')


def prepare(output, tag, dns):
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    generated = ROOT / 'generated/code/target-generated'
    base = generated / 'kubernetes-runtime/manifests/base'
    gke = ROOT / 'specs/YU17-otc-rates/generation/kubernetes/cluster/gke'
    docs = []
    inputs = []
    def include(path):
        inputs.append({'path': str(path.relative_to(ROOT)),
                       'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        docs.extend(read(path))
    # Explicit inherited cluster manifests; generated runtime service overrides
    # compose afterward. Sandbox, backups and seeding jobs are separate scopes.
    for name in ['statefulset-emptydir.yaml', 'gateway.yaml', 'nats.yaml',
                 'eod-price-db.yaml', 'eod-chain.yaml', 'risk-extract.yaml',
                 'reference-data.yaml', 'execution-algo-engine.yaml']:
        include(gke / name)
    for svc in ['trade-processor', 'people-service', 'account-service', 'trade-service', 'edge-proxy']:
        for kind in ['deployment', 'service']:
            include(base / f'{svc}-{kind}.yaml')
    for svc in ['web-front-end-angular', 'api-explorer', 'tempo', 'prometheus', 'loki', 'grafana', 'otel-collector']:
        for kind in ['deployment','service']: include(base / f'{svc}-{kind}.yaml')
    for name in ['observability-otel', 'observability-tempo', 'observability-prometheus',
                 'observability-loki', 'observability-grafana-datasources',
                 'observability-grafana-dashboard-providers','observability-grafana-dashboards']:
        include(base / f'{name}-configmap.yaml')
    include(base / 'edge-proxy-configmap.yaml')
    include(ROOT / 'specs/YU17-otc-rates/generation/kubernetes/frontend-gke/console.yaml')
    schema = read(base / 'database-init-configmap.yaml')
    inputs.append({'path':str((base/'database-init-configmap.yaml').relative_to(ROOT)),
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
            if image.startswith('traderx/') or 'docker.pkg.dev/traderx-' in image:
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
        if doc['metadata']['name'] == 'web-front-end-console':
            pod['serviceAccountName'] = 'web-front-end-console'
    # Structured objects first, exact bucket substitutions second. No substring
    # image/project replacement masquerading as deployment identity validation.
    raw = yaml.safe_dump_all(docs, sort_keys=False)
    for old,new in BUCKETS.items(): raw = raw.replace(old,new)
    raw = re.sub(r'resolver [0-9.]+ ', f'resolver {dns} ', raw)
    docs = list(yaml.safe_load_all(raw))
    validate(docs)
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
              'scope':'cold synthetic demo candidate; no retained-state, financial or performance acceptance',
              'resources':len(docs),'images':sorted(images),'inputFiles':inputs,
              'requiredSecretKeys':{k:sorted(v) for k,v in secrets.items()}, 'declaredRequests':requests,
              'workloadsWithoutCompleteRequests':unbounded,
              'candidateSha256':hashlib.sha256(raw.encode()).hexdigest(),
              'deploymentReady':False,
              'remainingGates':['publish and pin exact image digests','review per-image payload evidence',
                  'provision scoped storage credentials and private application secrets',
                  'approve additional engine-pool nodes','server dry-run and live end-to-end proof']}
    (output/'plan.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--tag',required=True)
    p.add_argument('--dns-ip',required=True)
    args=p.parse_args()
    import ipaddress
    ipaddress.ip_address(args.dns_ip)
    assert re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}',args.tag), 'invalid image tag'
    prepare(args.output.resolve(),args.tag,args.dns_ip)
