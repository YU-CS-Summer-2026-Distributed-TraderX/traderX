import urllib.request,urllib.error,json,re,hashlib,subprocess
from pathlib import Path
E=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');K=['kubectl','--kubeconfig',str(E/'kubeconfig'),'--context','kind-traderx-lr-20261008-01a11baa','-n','traderx']
def command(args):
 r=subprocess.run(args,capture_output=True,text=True,timeout=40);assert r.returncode==0,(args,r.stderr);return r.stdout
def get(path):
 try:
  with urllib.request.urlopen('http://127.0.0.1:28080'+path,timeout=20) as r:return r.status,r.read()
 except urllib.error.HTTPError as r:return r.code,r.read()
results={'candidateCommit':'8f4623d26251292a629b2e6a1c40dd9632e428ad','acceptedSource':'e274df81ff82bbba65238bd193c7a0dc54215f76','profile':'reviewedcandidateconsole actualAArch64 runtime onarm64kind; allnativecoreaccepted, LOCAL_ONLY1; fixedallowedruntimeRBAC/config; no source merge','routes':{},'assets':[]}
try:
 for path in ('/healthz','/auth/me','/m0/health','/m1/health','/m2/health','/order-matcher/health','/gateways','/trade-processor/recon/status'):
  status,body=get(path);results['routes'][path]={'status':status,'body':body.decode()};assert status==(401 if path=='/auth/me' else 200),(path,status,body)
  if path=='/auth/me':assert json.loads(body)['code']=='signed_out'
 gateways=json.loads(results['routes']['/gateways']['body']);assert gateways['count']==1 and gateways['gateways'][0]['health']['connected']==True and gateways['gateways'][0]['ready']==True
 for i in range(3):
  health=json.loads(results['routes'][f'/m{i}/health']['body']);assert health['trades']>=70 and health['engineApplied']>=0
 status,html=get('/');assert status==200 and b'<app-root' in html;(E/'lr05-candidate-index.html').write_bytes(html)
 assets=re.findall(rb'<script[^>]+src="([^"]+\.js)"',html);assert len(assets)>=2
 for asset in assets:
  asset=asset.decode();assert '..' not in asset and re.fullmatch(r'[A-Za-z0-9._/-]+',asset)
  status,body=get('/'+asset.lstrip('/'));assert status==200 and len(body)>100
  sha=hashlib.sha256(body).hexdigest();installed=command(K+['exec','deploy/web-front-end-console','--','sha256sum','/app/dist/web-front-end-console/browser/'+asset.lstrip('/')]).split()[0];assert sha==installed
  (E/('lr05-candidate-'+asset.replace('/','_'))).write_bytes(body);results['assets'].append({'path':asset,'bytes':len(body),'servedSha256':sha,'installedSha256':installed})
 results['pod']=json.loads(command(K+['get','pods','-l','app=web-front-end-console','-o','json']))
 assert len(results['pod']['items'])==1
 c=results['pod']['items'][0]['status']['containerStatuses'][0];assert c['ready'] and c['restartCount']==0
 results['verdict']='PASS named reviewed consolecandidate nativepod/HTML/assets/connected3member andgateway/projector-read surfaces; acceptedoriginal stillFAILED; broadLR05/cloud/authenticatedDeskexecution unclosed.'
finally:
 (E/'lr05-candidate-console.json').write_text(json.dumps(results,indent=2))
 (E/'lr05-candidate-console.log').write_text(command(K+['logs','deploy/web-front-end-console']))
print(results.get('verdict',results))
