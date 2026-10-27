#!/usr/bin/env python3
"""Add the Risk tab's Cloud Batch custody to a console Deployment (the hand addition a replace loses).

The risk-runtime console (package-state-YU18-risk-runtime.sh) carries the Python readers; this pins
WHICH private archive it hydrates into a read-only emptyDir before serving. Reads stay offline.
"""
import argparse,json,re
RUN='risk-cpu-cloud-20261023-022416'
ADMISSION='dcd68b478dc439a8bf4c6c8b37ccef89df50ea12187f5852f73fda979b7766c1'
HYDRATE=['--bucket','project-compsci-484414-risk-engine-pilot','--object',f'custody/{RUN}/archive.tar.gz',
 '--generation','1792722689402924','--archive-sha256','53cd9025655a0615646b2bc6ade74c0fbab255c6c46be7886d828945e1b61749',
 '--admission-sha256',ADMISSION,'--run-id',RUN,'--store','/batch']
PY='/opt/traderx/status-venv/bin/python'
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--console-deployment',required=True)
p.add_argument('--image',required=True)
a=p.parse_args()
if not re.fullmatch(r'us-east1-docker.pkg.dev/project-compsci-484414/traderx-demo/web-front-end-console@sha256:[0-9a-f]{64}',a.image):p.error('exact private CompSci console digest required')
d=json.load(open(a.console_deployment));assert d['metadata']['name']=='web-front-end-console' and d['metadata']['namespace']=='traderx'
for k in ('status',):d.pop(k,None)
for k in ('resourceVersion','uid','creationTimestamp','generation','managedFields'):d['metadata'].pop(k,None)
s=d['spec']['template']['spec'];assert len(s['containers'])==1
c=s['containers'][0];c['image']=a.image
env={'RISK_BATCH_DIRECTORY':f'/batch/{RUN}','RISK_BATCH_SHA256':ADMISSION,
 'RISK_BATCH_STATUS_SCRIPT':'/opt/traderx/risk-portfolio-tools/result_ingest.py','EOD_PYTHON':PY,
 'PYTHONDONTWRITEBYTECODE':'1','CONSOLE_CLI_CONCURRENCY':'2'}
c['env']=[{'name':k,'value':v} for k,v in env.items()]+[v for v in c.get('env',[]) if v['name'] not in env]
c['volumeMounts']=[m for m in c.get('volumeMounts',[]) if m['name']!='batch-custody']+[{'name':'batch-custody','mountPath':'/batch','readOnly':True}]
s['volumes']=[v for v in s.get('volumes',[]) if v['name']!='batch-custody']+[{'name':'batch-custody','emptyDir':{'sizeLimit':'32Mi'}}]
s['initContainers']=[i for i in s.get('initContainers',[]) if i['name']!='batch-custody']+[{'name':'batch-custody','image':a.image,
 'command':[PY],'args':['-B','/opt/traderx/risk-portfolio-tools/cloud_result.py','hydrate',*HYDRATE],
 'env':[{'name':'PYTHONDONTWRITEBYTECODE','value':'1'}],'volumeMounts':[{'name':'batch-custody','mountPath':'/batch'}],
 'resources':{'requests':{'cpu':'100m','memory':'128Mi'},'limits':{'cpu':'1','memory':'512Mi'}}}]
print(json.dumps(d,indent=2))
