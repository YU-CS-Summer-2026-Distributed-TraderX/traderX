import subprocess,json,sys,hashlib,time
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');epoch=sys.argv[1];assert epoch in ('epoch1','epoch2');out=E/epoch;out.mkdir(exist_ok=False)
K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,text=True,capture_output=True,timeout=45);assert r.returncode==0,(args,r.stderr);return r.stdout
resources={}
for kind in ('pods','deploy','sts','pvc','configmap','namespace'):
 args=K+['get',kind]+(['traderx'] if kind=='namespace' else [])+['-o','json'];raw=command(args);resources[kind]=json.loads(raw)
 if kind=='configmap':
  (out/'configmap-private-with-ca.json').write_text(raw)
  public=dict(resources[kind]);public['items']=[c for c in public['items'] if c['metadata']['name']!='kube-root-ca.crt'];(out/(kind+'.json')).write_text(json.dumps(public,indent=2))
 else:(out/(kind+'.json')).write_text(raw)
raw=command(['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','get','pv','-o','json']);(out/'pv.json').write_text(raw);resources['pv']=json.loads(raw)
# Logs are captured before stop; these are synthetic owned services, no secret export.
for p in resources['pods']['items']:
 name=p['metadata']['name'];raw=command(K+['logs',name,'--all-containers=true','--tail=2000']);(out/(name+'.log')).write_text(raw)
health=[json.loads(command(K+['exec',f'order-matcher-cluster-{i}','--','curl','-sS','http://localhost:8080/health'])) for i in range(3)];(out/'member-health.json').write_text(json.dumps(health,indent=2));assert all(h['engineApplied']>=0 for h in health)
# Quiesce only this owned fixture before preserving journals. No retained upgrade/recovery qualification.
command(K+['scale','deploy','--all','--replicas=0']);command(K+['scale','sts/order-matcher-cluster','--replicas=0'])
command(K+['wait','--for=delete','pod','--all','--timeout=90s'])
# Each selected hostPath belongs to the explicit namespace/PVC UID and an owned node.
archives=[]
for pv in resources['pv']['items']:
 spec=pv['spec'];claim=spec.get('claimRef',{})
 if claim.get('namespace')!='traderx':continue
 pvc=next(p for p in resources['pvc']['items'] if p['metadata']['name']==claim['name']);assert pvc['metadata']['uid']==claim['uid']
 terms=spec['nodeAffinity']['required']['nodeSelectorTerms'];matches=[m for t in terms for m in t['matchExpressions'] if m['key']=='kubernetes.io/hostname'];nodes=matches[0]['values'];assert len(nodes)==1 and nodes[0].startswith('traderx-lr-20261008-01a11baa-');node=nodes[0]
 path=spec['hostPath']['path'];assert path.startswith('/var/local-path-provisioner/')
 archive=out/(claim['name']+'.tar.gz')
 with archive.open('wb') as handle:
  r=subprocess.run(['docker','exec',node,'tar','-czf','-',path],stdout=handle,stderr=subprocess.PIPE,timeout=180);assert r.returncode==0,r.stderr
 archives.append({'pvc':claim['name'],'pvcUid':claim['uid'],'pv':pv['metadata']['name'],'node':node,'sourceHostPath':path,'archive':archive.name,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'bytes':archive.stat().st_size})
# Record exact outer container/mount ownership for deletion/identity across fresh storage.
containers=json.loads(command(['docker','inspect']+[f'traderx-lr-20261008-01a11baa-{suffix}' for suffix in ('control-plane','worker','worker2','worker3')]))
(out/'node-containers.json').write_text(json.dumps(containers,indent=2))
manifest={'epoch':epoch,'createdAtEpochMs':int(time.time()*1000),'namespaceUid':resources['namespace']['metadata']['uid'],'nodeContainers':[{'name':c['Name'],'id':c['Id'],'mounts':c['Mounts']} for c in containers],'archives':archives,'qualification':'owned synthetic fixture logs and stopped-service PVC byte archives; not crash-safe/retained migration/DR restore certification'}
(out/'export-manifest.json').write_text(json.dumps(manifest,indent=2));print([(a['pvc'],a['bytes']) for a in archives])
