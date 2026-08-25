
## 2026-08-25 — ALL NINE FORMAT-8 PROOFS GREEN; and one git index, two sessions

Chip `task_ea5b181f`, near-final. Full suite **35 passed / 3 failed**, **all nine format-8 proofs
GREEN**, the otel red attributed and fixed. All three late reds were *state a proof leaves behind*,
none of them the mint, all three fixed and re-run green. A confirming full run is in flight.

**`yu17-closed-survives-restart` LEADER arm GREEN** — member 0 restarted, replayed past the halt, all
three members read `phase=CLOSED`, and an order identical to the pre-restart probe is refused
`MARKET_CLOSED`. Follower arm green too. **Election and restore both exercised**, which is why the
leader arm was ruled in rather than left at the follower default.

### The index collision — two sessions, one worktree, one index

The UI lane's commit `7d8c8e4c` **contains 24 of the mint's files**. Verified here: 41 files total —
17 `web-front-end/` (the UI lane's), 13 `scripts/`, 6 `specs/`, 5 `issues/` (the mint's). The mint had
its files staged to check a `git mv` stat line — the trap where a resolve lands as a bare rename with
the body missing, **which fired, and staging is what caught it** — and the UI lane's `git commit` took
the whole index.

**Nothing is lost, the tree is correct, and the collar rename landed with its body (38 ± 5, not a bare
rename).** The only damage is attribution: `git log` says the format-8 mint was a UI change.

**The mint lane did not rewrite a peer's commit, and that was right.** A shared branch with two live
sessions is not one session's to rewrite. It committed its remainder as `c926fc79` with a READ THIS
FIRST paragraph pointing at `7d8c8e4c`, so the record reconstructs from `git log` alone.

**The tip is local-only** — `origin/YU17-otc-rates` is at `9cd30a45` — so a clean split remains
available. **Recommendation to yaakov: do not rewrite.** The tree is right, the reconstruction path
exists in two commit messages plus this entry, and a `reset --soft` on a worktree two live sessions
share risks a second collision worse than the bookkeeping it fixes. If he wants it done, it happens
when both lanes are idle and one session does it; both lanes have been told not to act unilaterally.

**The rule this exposes, and it is the half nobody had written down.** The standing memory rule —
*commit by pathspec, `diff --cached` first* — protects you from sweeping up a **peer's** work and does
**nothing** to stop a peer sweeping up **yours**. The only real protection is to **keep the index empty
except in the seconds around your own commit.** Belongs in `multi-agent-repo-coordination`.

### The three late reds, each a class find rather than a defect

- **`yu15-risk-extract`** read `accounts=3 halted=2` in-suite, `accounts=4 halted=0` standalone.
  `seed-proof-fixtures.sh` clears positions in generated throwaway instruments and **its prefix list
  had fallen nine behind the proofs** — three of the missing prefixes book trades and **two are the
  format-8 proof set's own**, so this chip began the leak. **The failure lands a full suite-length from
  its cause**, which is why it reads as a risk-extract defect and is not one. List now tracks the
  proofs, still scoped so `yu06-consumer-halt`'s genuine halt stays armed.
- **`yu05-recon`'s negative control stopped being one.** `fresh_classification()` returned on the first
  poll where `matched > 0` — **mid-sweep**. Step 3b plants its mismatch on the **oldest** row, which the
  sweep reaches **last**, so the control read `fieldMismatch=0`: exactly what a clean projection reads.
  **It could not tell "no mismatch" from "not looked yet"**, and the epoch only had to outgrow one
  poll's worth of sweep for it to go quiet. Now gated on quiescence of the whole triple.
- **`yu13-stp-and-replace` could not satisfy its own preflight, for ever.** `stp-boundary-fix` is
  today's tree unmodified, so its jar was byte-identical and Docker returned the **existing image with
  its original `Created` timestamp** — older than the generated sources. The guard refused the pair it
  had just asked for. **The guard was right every time; the build was lying.** Fixed with
  `--no-cache` on both sides. The docker-cache-defeats-mtime trap was already in memory; it just had
  no fix in the build script.

### The otel red: a readiness race, measured rather than rounded

`rollout status` returning does not mean the cluster can sequence a write. Isolated by varying one
thing at a time — mask on gateway alone fine, mask on members fine, so not the mask — then seeding at
fixed offsets after `rollout status` returned: **`+0s TimeoutException`, `+10s seeded`** and clean
thereafter. **Window under ten seconds, three consecutive reproductions, the proof lands inside it
every time.** Pods Ready means HTTP servers are listening, not that members have rejoined consensus —
the same gap `await_member_restored` exists to close. Fixed by retrying the fixture seed; **no
assertion touched.**

Filed with a second finding worth as much: **the gateway's 503 catch-all reports only
`e.getClass().getSimpleName()` and logs nothing** — a log capture across all three reproductions caught
no exception at all. That is why this cost an afternoon instead of a minute.

### The running tally, which is the story of the whole sequence

**Five arms that could never pass, one that could never fail, and three readings taken before the thing
they measure had finished.** Every one in a proof written before the build and never executed against
it. Writing proofs first is what made the mint provable; **executing them for the first time is what
found that a third of them could not discriminate.** Both halves are the lesson, and the second half
only arrives if the first is done.
