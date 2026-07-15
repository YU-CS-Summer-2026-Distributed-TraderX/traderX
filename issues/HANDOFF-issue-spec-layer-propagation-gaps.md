# Issue: spec-layer forward-propagation gaps (recurring pattern) + open instances

**Status: pattern documented 2026-07-14; YU04/YU05 instance FIXED same day; YU02 database-manifests
instance FIXED same day (committed on all 8 branches). No open instances.**

## The pattern

Each YUxx branch carries the spec packs (`specs/YUxx-*/`) of **itself and all ancestors** — never
descendants. Generation composes those layers cumulatively (last-wins overlay). This means a fix or
file added to a state's spec layer **after** descendant branches were cut does NOT reach those
branches automatically. Symptoms only appear when someone generates/brings up an *older* state from
a *newer* branch — which is exactly what the state-by-state demo tour does.

Known instances of this class (chronological):

1. **CORS origins** — `http://127.0.0.1:8080` added to `CORS_ALLOWED_ORIGINS` had to be hand-copied
   to every `*-deployment.yaml` across all 8 worktrees (fixed 2026-07-14).
2. **Spec-pack reformatting** — reformatted specs existed on home branches but descendants still
   carried none/old copies; initially misdiagnosed as a revert (resolved 2026-07-14, nothing lost).
3. **YU04/YU05 kind runtime manifests** (fixed 2026-07-14, commits on all 5 descendant branches):
   YU04's k8s layer (`database-init-configmap.yaml` with the 5 control-feed tables
   `account_control_outbox`, `account_source_epoch`, `stocks`, `stocks_control_outbox`,
   `stocks_source_epoch`; `reference-data-deployment.yaml` with first-ever DB/NATS env; plus
   account/database manifests) was committed on the YU04 home branch but absent from every
   descendant. Bringing YU04 up on kind from the YU08 worktree crashlooped `account-service`
   (`Table 'traderx.account_source_epoch' doesn't exist`) and `reference-data`
   (`ECONNREFUSED 127.0.0.1:3306` — mysql2 defaulting to localhost). Live cluster was hot-patched
   (`CREATE TABLE IF NOT EXISTS` + `kubectl set env` + restarts) instead of recreated; both YU04
   proofs (live-delta no-restart, offline catch-up) then green.

## Open instances

None. Most recently closed:

- **YU02-layer database manifests untracked on every branch (FIXED 2026-07-14).** The 2026-07-09
  kind MariaDB fix (`.../YU02-lmax-kubernetes/.../base/database-{deployment,service,init-configmap}.yaml`)
  existed only as untracked working-tree files, duplicated per worktree — while the *tracked*
  kustomization.yaml referenced all three, so a fresh clone could not bring YU02/YU03 up on kind.
  The YU08 copies were newest (explanatory headers + `protocol: TCP`, content otherwise identical
  to the other 5 worktrees'); committed verbatim on all 8 branches. Note: the legacy
  `lmax-kubernetes` branch (pre-YU02-rename, carries `specs/lmax-kubernetes`) was deliberately NOT
  updated — it predates the YUxx layout entirely; the YU02 blp-ha branch is the effective home.

## Convention going forward

When a fix lands in an **ancestor state's** spec layer (`specs/YUxx-*/generation/...`) on its home
branch, immediately copy it to that path on **every descendant branch** and commit — the
`spec-pack-audit` skill's cross-worktree diff is the verification step. Rule of thumb: a spec-layer
file's md5 should be identical across every branch that carries it (the home branch is
authoritative when they differ).
