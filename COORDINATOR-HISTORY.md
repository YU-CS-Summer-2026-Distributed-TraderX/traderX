
## 2026-08-24 — THE HEADLINE, found after the fix: the quorum kill was a 36-day-old propagation gap

The probe lane went looking for why `gke/statefulset-emptydir.yaml` already had tcpSocket, and the
answer reframes the whole day. **Verified from the tree:**

  c9ffb5eb  2026-07-19  "YU12: harden health server so 134k flood is sustainable (no crash-cascade)"
            changed httpGet -> tcpSocket in gke/statefulset-emptydir.yaml ONLY, plus ClusterNodeMain

That commit diagnosed the IDENTICAL failure — k8s SIGKILLing alive members, leader loss, crash-loop,
recovery death-spiral — and shipped the IDENTICAL fix, **thirty-six days ago, into one of three
manifest variants.** The kind manifest has carried `httpGet` untouched since its creation (`236acebb`)
and the PVC-gke one likewise. On 2026-08-24 the same mechanism took the kind quorum out overnight,
9/10/11 restarts, three simultaneous kills inside one second.

**So this was not a new defect and I did not discover it. A fix that already existed simply never
travelled**, and I spent the morning re-deriving a diagnosis the repo had held since July. The class is
the one `CLAUDE.md` opens with and the one this project keeps paying for; the issue now names the
propagation-gaps handoff as its class rather than presenting the finding as new.

**What makes it worse than an ordinary miss**: nothing was broken in between. The emptydir variant is
not the one the rigs run, so the fixed copy was never exercised and the unfixed copies never looked
wrong. The gap was invisible until contention arrived — which is the shadowed-layer failure mode
exactly, one directory over from where the rule usually bites.

Four issues filed today, all substantial: `a-per-member-liveness-probe-fires-on-a-global-condition`
(117 lines), `the-algo-recovery-verdict-stack-never-reached-yu08-yu16` (84),
`the-cluster-tier-exports-no-risk-gauge` (82), `the-manifests-pin-a-build-the-rig-no-longer-runs` (44).

**The algo lane closed both its issues** — `2b40524d`, `82292970`, `dc1bada7` — with the (C) pins
asserting measured behaviour as I required, and reported that the `git mv` first staged as
`2 files changed, 0 insertions(+)`, the pure-rename-body-missing shape, caught on the stat line. It
also audited its own commits both directions after the fact and reported the result as **"clean, but by
luck, not by method"** — it had used bare `git commit` three times in a shared worktree before my rule
arrived. That is the most useful sentence any lane sent today.
