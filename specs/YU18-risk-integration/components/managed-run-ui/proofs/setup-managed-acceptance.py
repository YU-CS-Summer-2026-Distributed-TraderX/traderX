from pathlib import Path
import subprocess,time,json,os
e=Path(os.environ['OUT']).resolve();g=Path(os.environ['GEN']).resolve();e.mkdir(parents=True,exist_ok=True)
for name in ['traderx-ma-20260925-sql','traderx-ma-20260925-nats']:
 if subprocess.run(['docker','inspect',name],capture_output=True).returncode==0:raise RuntimeError('Refusing existing container: '+name)
def run(args,**kw):
 r=subprocess.run(args,capture_output=True,text=True,**kw);(e/'setup-commands.jsonl').open('a').write(json.dumps({'args':args,'exit':r.returncode,'stdout':r.stdout,'stderr':r.stderr})+'\n');r.check_returncode()
 if args[:3]==['docker','run','-d']:
  with (e/'owned-container-ids.txt').open('a') as f:f.write(r.stdout.strip()+'\n')
 return r.stdout
run(['docker','run','-d','--name','traderx-ma-20260925-sql','--memory','512m','-p','127.0.0.1:27806:3306','-e','MARIADB_ROOT_PASSWORD=local-proof','-e','MARIADB_DATABASE=traderx','-e','MARIADB_USER=traderx','-e','MARIADB_PASSWORD=traderx','mariadb:11.4','--lower_case_table_names=1'])
for _ in range(60):
 r=subprocess.run(['docker','exec','traderx-ma-20260925-sql','mariadb','-utraderx','-ptraderx','traderx','-e','SELECT 1'],capture_output=True)
 if r.returncode==0:break
 time.sleep(1)
else:raise RuntimeError('SQL not ready')
# Explicit NEW empty database setup: generated additive desired-state schema contains
# reference accounts only, never demo trades or positions. Do not run the demo initializer.
raw=(g/'kubernetes-runtime/manifests/base/database-init-configmap.yaml').read_text();key='  900-migrations.sql: |\n';assert raw.count(key)==1
lines=raw.split(key)[1].splitlines();assert all(not x.strip() or x.startswith('    ') for x in lines)
sql='\n'.join(x[4:] if x.startswith('    ') else '' for x in lines)+'\n'
assert 'INSERT INTO trades' not in sql and 'INSERT INTO positions' not in sql
(e/'new-empty-schema.sql').write_text(sql)
for name,body in [('new-empty-schema.sql',sql),('ri06.sql',(g/'postgres-database-replacement/mariadb-migrations/ri06.sql').read_text()),('ri06-event-recovery.sql',(g/'postgres-database-replacement/mariadb-migrations/ri06-event-recovery.sql').read_text())]:
 (e/name).write_text(body);run(['docker','exec','-i','traderx-ma-20260925-sql','mariadb','-utraderx','-ptraderx','traderx'],input=body)
(e/'nats.conf').write_text('port:4222\nhttp_port:8222\njetstream {store_dir:"/tmp/js"}\nwebsocket {port:8080\nno_tls:true}\n')
run(['docker','run','-d','--name','traderx-ma-20260925-nats','--memory','128m','-p','127.0.0.1:27822:4222','-p','127.0.0.1:27823:8222','-p','127.0.0.1:27880:8080','-v',str(e/'nats.conf')+':/etc/nats/nats.conf:ro','nats:2.10-alpine','-c','/etc/nats/nats.conf'])
