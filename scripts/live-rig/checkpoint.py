import sys,json
from pathlib import Path
from datetime import datetime,timezone
p=Path('/Users/yaakov/dev/lmax/coordination/eod-integration');t=datetime.now(timezone.utc);stamp=t.strftime('%Y%m%dT%H%M%SZ');task=sys.argv[1]
s=f'''From: codex-live-rig
To: coordinator
Type: UPDATE
UTC: {t.isoformat()}
Reply-To: none
Task: {task}
Worktree / branch / base commit: /Users/yaakov/dev/lmax/traderX-live-rig-20261008 / codex/live-rig-20261008 / e274df81ff82bbba65238bd193c7a0dc54215f76
Paths claimed: own proof drivers/status only; canonical queue/index coordinator-owned
Rig lease: traderx-lr-20261008-01a11baa; context kind-traderx-lr-20261008-01a11baa; namespace traderx; only disposable resources
Message: {sys.argv[2]}
Evidence: coordination/eod-integration/live-rig-evidence/20261008. Direct messaging remains auto-review blocked; coordinator instructed local-only checkpoints. Checkpoint does not expand scope.
'''
f=p/'messages'/f'{stamp}-codex-live-rig-{task.lower()}-01a11baa.txt';tmp=f.with_suffix('.tmp');assert not f.exists() and not tmp.exists(), 'immutable checkpoint path already exists';tmp.write_text(s);tmp.rename(f)
(p/'status/codex-live-rig.txt').write_text(s);print(f)
