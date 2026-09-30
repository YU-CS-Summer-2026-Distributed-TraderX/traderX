#!/usr/bin/env python3
"""Audit the actual production artifact, not Markdown filename guesses."""
import argparse,json,re
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin,urlsplit,unquote
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--build',type=Path,default=ROOT/'website/build');p.add_argument('--report',type=Path,default=Path('/private/tmp/traderx-public-docs-audit.json'));a=p.parse_args()
class Page(HTMLParser):
 def __init__(self):super().__init__();self.links=[];self.ids=set();self.text=[];self.prose=[];self.stack=[]
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag not in ['meta','link','img','br','hr','input','source','wbr']:self.stack.append(tag)
  if 'id' in d:self.ids.add(d['id'])
  if tag=='a' and d.get('href'):self.links.append(d['href'])
 def handle_endtag(self,tag):
  if tag in self.stack:self.stack=self.stack[:len(self.stack)-1-self.stack[::-1].index(tag)]
 def handle_data(self,data):
  if not any(x in self.stack for x in ['script','style']):self.text.append(data)
  if not any(x in self.stack for x in ['script','style','pre','code','blockquote','svg']):self.prose.append(data)
if not (a.build/'index.html').exists():raise SystemExit('FAIL: production homepage absent')
pages={}
for f in a.build.rglob('*.html'):
 page=Page();page.feed(f.read_text());route='/traderX/'+str(f.relative_to(a.build)).removesuffix('.html');route=route.removesuffix('/index');pages[route]=page
broken=[];anchors=[];dashes=[];private=[]
for route,page in pages.items():
 for url in page.links:
  u=urlsplit(urljoin('https://local'+route,url))
  if u.netloc not in ['local','YU-CS-Summer-2026-Distributed-TraderX.github.io','yu-cs-summer-2026-distributed-traderx.github.io']:continue
  target=unquote(u.path).rstrip('/')
  if target in pages:
   if u.fragment and unquote(u.fragment) not in pages[target].ids:anchors.append([route,url])
  elif not (a.build/target.removeprefix('/traderX/')).is_file():broken.append([route,url])
 for text in page.prose:
  if '—' in text:dashes.append([route,text.strip()[:240]])
 for token in ['firstEODProposal','eod-response-to-alex','Notes for Alex','traderx-501015-risk-extracts/2026-07-23','docs/risk-integration/chat']:
  if token in ''.join(page.text):private.append([route,token])
exports={}
llm_broken=[]
for f in list(a.build.glob('*search*.json'))+list(a.build.glob('llms*.txt')):
 text=f.read_text();hits=[t for t in ['firstEODProposal','eod-response-to-alex','Notes for Alex','traderx-501015-risk-extracts/2026-07-23','/docs/risk-integration/chat'] if t in text];exports[f.name]=hits
 if f.name=='llms.txt':
  for url in re.findall(r'\]\((https://[^)]+)\)',text):
   u=urlsplit(url)
   if u.netloc.lower()!='yu-cs-summer-2026-distributed-traderx.github.io':continue
   target=unquote(u.path).rstrip('/')
   if target not in pages and not (a.build/target.removeprefix('/traderX/')).is_file():llm_broken.append(url)
route_map=[]
for f in (ROOT/'website/.docusaurus/docusaurus-plugin-content-docs').rglob('*.json'):
 try:d=json.loads(f.read_text())
 except ValueError:continue
 if isinstance(d,dict) and d.get('source') and d.get('permalink') in pages:route_map.append({k:d.get(k) for k in ['source','permalink','id','title']})
report=dict(html_pages=len(pages),source_routes=route_map,broken_targets=broken,broken_anchors=anchors,llm_broken_targets=llm_broken,prose_em_dashes=dashes,private_content=private,exports=exports)
a.report.parent.mkdir(parents=True,exist_ok=True);a.report.write_text(json.dumps(report,indent=2))
print(json.dumps({k:len(v) if isinstance(v,list) else v for k,v in report.items() if k!='source_routes'},indent=2));print('Report:',a.report)
raise SystemExit(1 if broken or anchors or llm_broken or private or any(exports.values()) else 0)
