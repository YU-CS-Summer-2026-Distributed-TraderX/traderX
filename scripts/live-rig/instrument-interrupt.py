from pathlib import Path
import shutil,difflib
root=Path(__file__).resolve().parents[2];e=Path('/Users/yaakov/dev/lmax/coordination/eod-integration/live-rig-evidence/20261008');target=e/'fault-source-interrupt'
shutil.copytree(e/'fault-source-surviving',target,ignore=shutil.ignore_patterns('build','.gradle'),dirs_exist_ok=False)
p=target/'src/main/java/finos/traderx/ordermatcher/cluster/ClusterGatewayMain.java';original=p.read_text();s=original
needle='''            final ExecResult result = p.future.get(waitBudgetMs, TimeUnit.MILLISECONDS);'''
replacement='''            if (java.nio.file.Files.exists(java.nio.file.Path.of("/tmp/lr-interrupt-waiters"))) {
                final Thread waiter = Thread.currentThread();
                final Thread control = new Thread(() -> {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) { return; }
                    System.out.println("LR-WAITER-INTERRUPT key=" + p.clientKey);
                    waiter.interrupt();
                }, "lr-interrupt-control");
                control.setDaemon(true); control.start();
            }
            final ExecResult result = p.future.get(waitBudgetMs, TimeUnit.MILLISECONDS);'''
assert s.count(needle)==1;s=s.replace(needle,replacement)
needle='''"LR-OWNER-RETIRED key=" + p.clientKey + " depth=" + inflight.depth()'''
replacement='''"LR-OWNER-RETIRED key=" + p.clientKey + " depth=" + inflight.depth() + " cause=" + e.getClass().getSimpleName()'''
assert s.count(needle)==1;s=s.replace(needle,replacement)
p.write_text(s);(e/'gateway-interrupt.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True),s.splitlines(True),fromfile='surviving/ClusterGatewayMain.java',tofile='interrupt/ClusterGatewayMain.java')))
print(target)
