# Automatic projection recovery tasks

Status: implemented; review R1/R2 corrected, local review pending, 2026-09-25. Levels: **source** (code read/review),
**generated** (tests run on the generated tree), **fixture** (real MariaDB, synthetic source pages,
injected faults), **local-live** (real single-member Aeron runs with archives and HTTP gateways,
real NATS, MariaDB from the generated ConfigMap 900 schema + three additive migrations with
`ddl-auto=validate`, trade-processors as child JVMs). No HA claim.

- [x] Worktree/branch from 5ceb0be3, CLAIM, operative YU18 layers identified (see plan).
- [x] Member page, consumer worker, migration, disposable profile, status route.
- [x] Fixture suite 19/19 after R1/R2 (15 before); explicit EventRecoveryPersistenceIT 17/17 retained; trade-processor unit 102/102; matcher ClusterRecon*/RunIdentity* 27/27 (incl. real 3-member archive consensus test).
- [x] Live proof through the launcher: PASS; generated parity 198/198; five gates pass.
- [x] Docs, RI06, backlog, evidence manifest, commit, READY_FOR_REVIEW.

## Acceptance mapping

| Case | Evidence | Level |
|---|---|---|
| Unchanged baseline failure | Auto disabled: consumer restart keeps 2 of 4 legs, no cursor (`APR_BASELINE_UNCHANGED`) | local-live |
| Ongoing trading throughout | Trader active during every recovery phase; 61 pages committed while trading, 27 inserting missed trades (`APR_TRADER_STOP`) | local-live |
| Consumer outage/restart | Stop + restart with profile; converges, archive match | local-live |
| Publisher/NATS outage, consumer connected | NATS container stopped/started, consumer alive throughout; periodic path repairs | local-live |
| Process kill | External SIGKILL mid-stream, restart converges | local-live |
| Restart before/after commit | JVM `halt(137)` at `before-commit` (log/cursor unchanged) and `after-commit` (exactly one page) | local-live (exact-point halt via test-only hook) |
| Injected exceptions before/after commit | rollback + retry; post-commit retry idempotent | fixture |
| Interior and per-command gaps | Interior + seq with order but no trade | fixture; interior also live |
| Whole-command paging, zero-output commands | page boundaries [1,3,4,5] at size 2 | fixture |
| Duplicate / out-of-order delivery | re-delivery after catch-up changes nothing | fixture |
| R1: all trades retained, out of order through flat | basis 200 -> 300 (source order), CURRENT, one notification, idle rerun silent | fixture |
| R1: flip without flat | converges with no corrective write or notification | fixture |
| R1: retained-only key with unexplained balance | BLOCKED, SQL unchanged, no notification | fixture |
| R2: normal catch-up notifies final position | one publish qty 20 / basis 150 after commit | fixture |
| Newer live state not overwritten | order at newer seq kept; newer trade kept in rebuilt position; basis in authoritative order | fixture |
| Two workers, fenced stale owner | SIGSTOP owner, takeover (fence 6->7, 7.1 s), SIGCONT: 0 commits after resume, fences monotonic | local-live |
| Owner stalled inside transaction | server aborts after lease TTL; only new fence in log; control without the bound fails (stale fence committed) | fixture |
| Selected-run transition during catch-up | freeze while `CATCHING_UP`; VERIFY passes with no operator call; select/activate; next-live converges; sealed and legacy rows unchanged | local-live; mid-page supersede fixture |
| DRAINING beyond frozen | refuses `RUN_SCOPE_NOT_WRITABLE` | fixture |
| Conflict refusal | Altered retained trade: BLOCKED, SQL unchanged; resumes after reviewed repair | local-live + fixture |
| Orphan, unexplained balance, history change, checkpoint/cursor identity, missing archive | BLOCKED, no writes | fixture (archive refusal via peer response; member strict replay is the reviewed existing code, source) |
| Canonical chain independent of publish clock | ClusterReconCatchupTest | generated unit |
| SQL timestamp precision 0/3/6 | live DB is DATETIME(0); auto fixture at precision 0 incl. stored-corruption refusal; explicit 0/3/6 suite retained | local-live + fixture |
| Exact economics / final orders / positions | archive `/recon/recovery-events` comparison: fresh-live 946 legs, 946 final orders, +1441/-1441 basis 100; next-live 186, +276/-276 | local-live |
| No operator catch-up | `projection_recovery` has 0 rows; test class has no catch-up call | local-live |

## Failures and corrections during the lane

1. Fixture: page loop gated on the thread flag (never ran when called directly). Fixed (`stopped` flag).
2. Fixture: at DATETIME(0), order rows were verified after the page wrote them (JPA returned unrounded values). Fixed: verify before writing.
3. Live 1-2: harness; child JVM inherited test-classpath H2/`create-drop` settings; NATS restart remapped its port. Fixed in the harness.
4. Live 3: the member chain hashed the NATS envelope, whose trade `date` is the publish clock; the cursor correctly refused. Fixed: canonical type+payload; unit test added.
5. Live 4/6: an owner SIGSTOPped inside its transaction blocked takeover (61 s once, >180 s once) and stalled live booking. Fixed with the idle-transaction bound; fixture control proves it.
6. Live 5: test picked a victim below the cursor (documented limitation). Live 7: transition VERIFY hit `max_allowed_packet` (pre-existing; disposable DB raised).

## Coordinator review R1/R2 (2026-09-25)

Reproduced by the coordinator (review-evidence/automatic-recovery-coordinator). R1: only keys
with inserted trades were checked/re-derived, so arrival-order basis could be certified CURRENT
and retained-only keys skipped the balance check. R2: notification compared against the
post-insertion row. Fix: every page key is balance-checked and re-derived; notify vs pre-page
value. Control: the corrected tests on the delivered b5b12bbb code fail 3 (flat-basis,
retained-only balance, R2 notification); corrected code 19/19. Launcher rerun `final-r1`: PASS
all. In `final-r1` the freeze landed after the worker was CURRENT; the freeze-while-CATCHING_UP
observation is from the earlier `final` run (same launcher, pre-R1 code).
