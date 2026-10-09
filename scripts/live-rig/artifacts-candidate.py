import subprocess,json,hashlib
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa']
def run(args):
 r=subprocess.run(args,capture_output=True,text=True,timeout=40);assert r.returncode==0,(args,r.stderr);return r.stdout
pods=json.loads(run(K+['-n','traderx','get','pods','-o','json']));(E/'lr05-candidate-pods.json').write_text(json.dumps(pods,indent=2))
nodes=[i['metadata']['name'] for i in json.loads(run(K+['get','nodes','-o','json']))['items']];assert len(nodes)==4
refs=sorted(set(c['image'] for p in pods['items'] for c in p['spec']['containers'] if c['image'].startswith('traderx/')))
report={'source':'e274df81ff82bbba65238bd193c7a0dc54215f76','consoleCandidate':'8f4623d26251292a629b2e6a1c40dd9632e428ad','nodes':nodes,'images':{},'mismatchControls':[]}
for ref in refs:
 expected=json.loads(run(['docker','image','inspect',ref]))[0]
 info={'dockerId':expected['Id'],'architecture':expected['Architecture'],'layers':expected['RootFS']['Layers'],'nodes':{}}
 for node in nodes:
  d=json.loads(run(['docker','exec',node,'crictl','inspecti','docker.io/'+ref]));(E/('lr05-candidate-'+node+'-'+ref.split('/')[1].split(':')[0]+'.json')).write_text(json.dumps(d,indent=2))
  actual=d['info']['imageSpec'];assert actual['rootfs']['diff_ids']==expected['RootFS']['Layers'],(ref,node,'layer mismatch')
  assert actual['architecture']==expected['Architecture'],(ref,node,'architecture mismatch')
  for field in ('Entrypoint','Cmd','Env','WorkingDir'):
   assert actual.get('config',{}).get(field)==expected['Config'].get(field),(ref,node,field)
  info['nodes'][node]={'criId':d['status']['id'],'repoTags':d['status']['repoTags'],'repoDigests':d['status']['repoDigests'],'rootfsAndRuntimeConfigMatch':True}
 report['images'][ref]=info
# Mismatched intended reference must fail read-only admission on nonempty real node inventory.
for ref in ['traderx/cluster-node:lr-20261008-01a11baa','traderx/cluster-node:lr-20261008-01a11baa-NONEXISTENT']:
 r=subprocess.run(['python3','scripts/yu15/image-admission.py','node-refs','--context','kind-traderx-lr-20261008-01a11baa','--image',ref],capture_output=True,text=True,timeout=45)
 report['mismatchControls'].append({'reference':ref,'exit':r.returncode,'stdout':r.stdout,'stderr':r.stderr})
assert report['mismatchControls'][0]['exit']==0 and report['mismatchControls'][1]['exit']!=0
for p in pods['items']:
 for c in p['status'].get('containerStatuses',[]):
  ref=c['image'];norm=ref.removeprefix('docker.io/')
  if norm in report['images']:
   node=p['spec']['nodeName'];actual=report['images'][norm]['nodes'][node]['criId']
   assert c['imageID'] in report['images'][norm]['nodes'][node]['repoDigests'] or c['imageID'].split('@')[-1]==actual,(p['metadata']['name'],c['imageID'],actual)
report['verdict']='PARTIAL content verification:7 local image references rootfs/runtime config agree on4 nodes and pod resolved IDs agree; reviewed actualAArch64 candidateconsole nowboots andserves connectedreads; originalmodule/platformfailures retainedseparately. No canonicalintegration/cloud/fullreleasequalification asserted.'
(E/'lr05-candidate-identities.json').write_text(json.dumps(report,indent=2));print(report['verdict'])
