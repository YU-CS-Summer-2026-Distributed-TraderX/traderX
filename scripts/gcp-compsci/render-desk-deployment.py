#!/usr/bin/env python3
"""Derive a single-replica hosted operator Desk from the admitted console configuration."""
import argparse,copy,json,re
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--console-deployment',required=True)
p.add_argument('--image',required=True)
a=p.parse_args()
if not re.fullmatch(r'us-east1-docker.pkg.dev/project-compsci-484414/traderx-demo/web-front-end-desk@sha256:[0-9a-f]{64}',a.image):p.error('exact private CompSci Desk digest required')
x=json.load(open(a.console_deployment));assert x['metadata']['name']=='web-front-end-console' and x['metadata']['namespace']=='traderx'
s=copy.deepcopy(x['spec']['template']['spec']);assert len(s['containers'])==1
c=s['containers'][0];c['image']=a.image
updates={'DESK_DEMO_PROFILE':'1','DESK_USERS_FILE':'/data/desk/users.json','STATIC_ROOT':'/app/desk-dist/browser','DEMO_CONSOLE_URL':'https://demo.yaakovseif.dev/'}
c['env']=[v for v in c['env'] if v['name'] not in updates and v['name']!='DESK_RISK_CONTROL_TOKEN']
c['env'] += [{'name':k,'value':v} for k,v in updates.items()]
c['env'].append({'name':'DESK_RISK_CONTROL_TOKEN','valueFrom':{'secretKeyRef':{'name':'auth-secrets','key':'risk-control-token'}}})
c.setdefault('volumeMounts',[]).append({'name':'desk-users','mountPath':'/data/desk'})
s.setdefault('volumes',[]).append({'name':'desk-users','persistentVolumeClaim':{'claimName':'desk-users'}})
meta=lambda n:{'name':n,'namespace':'traderx'}
items=[{'apiVersion':'v1','kind':'PersistentVolumeClaim','metadata':meta('desk-users'),'spec':{'accessModes':['ReadWriteOnce'],'storageClassName':'standard-rwo','resources':{'requests':{'storage':'1Gi'}}}},
{'apiVersion':'apps/v1','kind':'Deployment','metadata':meta('web-front-end-desk'),'spec':{'replicas':1,'strategy':{'type':'Recreate'},'selector':{'matchLabels':{'app':'web-front-end-desk'}},'template':{'metadata':{'labels':{'app':'web-front-end-desk'}},'spec':s}}},
{'apiVersion':'cloud.google.com/v1','kind':'BackendConfig','metadata':meta('desk-https'),'spec':{'securityPolicy':{'name':'traderx-console-network'},'healthCheck':{'type':'HTTP','port':8080,'requestPath':'/healthz'}}},
{'apiVersion':'v1','kind':'Service','metadata':dict(meta('desk-https'),annotations={'cloud.google.com/neg':'{"ingress":true}','cloud.google.com/backend-config':'{"default":"desk-https"}'}),'spec':{'type':'ClusterIP','selector':{'app':'web-front-end-desk'},'ports':[{'name':'http','port':8080,'targetPort':8080}]}},
{'apiVersion':'networking.gke.io/v1','kind':'ManagedCertificate','metadata':meta('desk-https'),'spec':{'domains':['desk.yaakovseif.dev']}}]
print(json.dumps({'apiVersion':'v1','kind':'List','items':items},indent=2))
