from pathlib import Path
import shutil, difflib
root=Path(__file__).resolve().parents[2]
e=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008')
target=e/'fault-source'
shutil.copytree(root/'generated/code/target-generated/order-matcher',target,ignore=shutil.ignore_patterns('build','.gradle'),dirs_exist_ok=False)
p=target/'src/main/java/finos/traderx/ordermatcher/cluster/ClusterGatewayMain.java'
original=p.read_text();s=original
needle='''                final FutureTask<?> task = inflight.depth() == 0'''
replacement='''                if (java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/lr-owner-before"))) {
                    System.out.println("LR-OWNER-BEFORE queue=" + tasks.size() + " depth=" + inflight.depth());
                    while (java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/lr-owner-before"))) Thread.sleep(5);
                    System.out.println("LR-OWNER-RESUMED queue=" + tasks.size() + " depth=" + inflight.depth());
                }
                final FutureTask<?> task = inflight.depth() == 0'''
assert s.count(needle)==1;s=s.replace(needle,replacement)
needle='queuedTask = new OwnerTask<>(() -> { offerPipelined(p); return null; });'
replacement='''queuedTask = new OwnerTask<>(() -> {
                if (java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/lr-owner-after"))) {
                    System.out.println("LR-OWNER-STARTED key=" + p.clientKey);
                    while (java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/lr-owner-after"))) Thread.sleep(5);
                    System.out.println("LR-OWNER-START-RESUMED key=" + p.clientKey);
                }
                offerPipelined(p); return null; });'''
assert s.count(needle)==1;s=s.replace(needle,replacement)
needle='''                inflight.release(); // retirement owns only this acquired, never-started slot'''
replacement='''                inflight.release(); // retirement owns only this acquired, never-started slot
                System.out.println("LR-OWNER-RETIRED key=" + p.clientKey + " depth=" + inflight.depth());'''
assert s.count(needle)==1;s=s.replace(needle,replacement)
p.write_text(s)
(e/'gateway-fault.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True),s.splitlines(True),fromfile='accepted/ClusterGatewayMain.java',tofile='fault/ClusterGatewayMain.java')))
print(target)
