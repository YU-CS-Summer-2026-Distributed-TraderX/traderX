from pathlib import Path
import yaml
root=Path(__file__).resolve().parents[2]
base=root/'generated/code/target-generated/kubernetes-runtime/manifests/base'
docs=[]
for p in sorted(base.glob('observability-*-configmap.yaml')):
 docs.extend(yaml.safe_load_all(p.read_text()))
for service in ('otel-collector','tempo','prometheus','grafana','loki','blackbox-exporter'):
 for suffix in ('deployment','service'):
  docs.extend(yaml.safe_load_all((base/f'{service}-{suffix}.yaml').read_text()))
for suffix in ('deployment','service'):
 docs.extend(yaml.safe_load_all((base/f'edge-proxy-{suffix}.yaml').read_text()))
routes={'reference-data':'reference-data:18085','position-service':'position-service:18090','trade-processor':'trade-processor:18091','price-publisher':'price-publisher:18100','order-matcher':'order-matcher:18110','algo':'execution-algo-engine:18120','tempo':'tempo:3200','grafana':'grafana:3000','prometheus':'prometheus:9090'}
conf='server { listen 8080; resolver 10.96.0.10 valid=10s; location = /health { return 200 "ok"; }\n'
for route,target in routes.items():
 path='/algo/' if route=='algo' else ('/grafana/' if route=='grafana' else '/')
 conf+=f'location /{route}/ {{ proxy_pass http://{target}{path}; }}\n'
conf+='location ~ ^/m([0-2])/(.*)$ { set $member_host order-matcher-cluster-$1.order-matcher-cluster.traderx.svc.cluster.local; proxy_pass http://$member_host:8080/$2$is_args$args; }\nlocation /nats-ws { proxy_pass http://nats:8081; proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; }\nlocation / { return 503 "legacy route unavailable in local acceptance profile"; } }'
docs.append({'apiVersion':'v1','kind':'ConfigMap','metadata':{'name':'edge-proxy-config','namespace':'traderx'},'data':{'default.conf':conf}})
console=list(yaml.safe_load_all((root/'specs/YU17-otc-rates/generation/kubernetes/frontend-gke/console.yaml').read_text()))
for d in console:
 if d and d['kind']=='Deployment':
  pod=d['spec']['template']['spec'];pod.pop('nodeSelector',None)
  c=pod['containers'][0];c['image']='traderx/console:lr-20261008-01a11baa';c['imagePullPolicy']='IfNotPresent';c['env'].append({'name':'LOCAL_ONLY','value':'1'})
 docs.append(d)
docs.append({'apiVersion':'v1','kind':'Service','metadata':{'name':'web-front-end-console','namespace':'traderx'},'spec':{'selector':{'app':'web-front-end-console'},'ports':[{'port':8080,'targetPort':8080}]}})
print(yaml.safe_dump_all([d for d in docs if d],sort_keys=False))
