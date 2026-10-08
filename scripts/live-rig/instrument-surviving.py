from pathlib import Path
import shutil,difflib,sys
root=Path(__file__).resolve().parents[2];e=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008')
role=sys.argv[1];assert role in ('surviving','omission')
target=e/('fault-source-'+role)
shutil.copytree(e/'fault-source',target,ignore=shutil.ignore_patterns('build','.gradle'),dirs_exist_ok=False)
p=target/'src/main/java/finos/traderx/ordermatcher/cluster/ClusterGatewayMain.java';original=p.read_text();s=original
for mode in ('before','after'):
 needle=f'while (java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/lr-owner-{mode}"))) Thread.sleep(5);'
 replace=f'''while (java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/lr-owner-{mode}"))) {{
                        client.pollEgress(); client.sendKeepAlive(); Thread.sleep(5);
                    }}'''
 assert s.count(needle)==1;s=s.replace(needle,replace)
if role=='omission':
 needle='''            if (!startState.compareAndSet(WAITING, RETIRED)) {'''
 replace='''            if (true) { // DELIBERATE NEGATIVE CONTROL: omit the actual retirement claim'''
 assert s.count(needle)==1;s=s.replace(needle,replace)
p.write_text(s)
(e/('gateway-'+role+'.patch')).write_text(''.join(difflib.unified_diff(original.splitlines(True),s.splitlines(True),fromfile='original-fault/ClusterGatewayMain.java',tofile=role+'/ClusterGatewayMain.java')))
print(target)
