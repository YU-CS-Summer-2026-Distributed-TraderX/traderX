# 02 — Test-coverage inventory and map

> **Do this now, in parallel with 01.** It's cheap, nothing gates it, and it produces both a work plan
> and a presentation slide. The premise of the testing ask is that we're under-tested — **in the engine
> layer we're not**, and nobody can see it. Lane: investigation + documentation. See [[00-INDEX]].

## Why this first (before writing any new test)

We already have substantial green suites — **YU13 269 / YU14 283 / YU15 300**, plus 4 allocation gates,
`noGcTest`, 2 Epsilon gates, and byte-identical determinism verification across all three members after
millions of orders. Writing new tests before mapping what exists risks duplicating that and spending the
budget in the wrong place. **The gaps are the inherited vanilla services and cross-service integration**,
not the matching engine.

## The job

1. **Inventory what exists**, per component and per state: unit tests, integration tests, the allocation
   / `noGcTest` / Epsilon gates, the determinism checks, and the runnable shell/node proof scripts in
   `scripts/bench/` (several capabilities — cancel, suppression, replace, risk extract, FIX status — are
   covered by *falsifiable proof scripts* rather than JUnit; **that counts as testing but it is not in
   CI**, which is itself a finding).
2. **Map coverage against the component list**: the inherited services (account-service, reference-data,
   position-service, trade-processor, people-service, web-front-end) versus our own layers (matching
   engine, cluster/consensus, gateway REST/FIX/binary, risk gateway, read model, EOD/risk extract).
3. **Name the gaps and rank them** by (a) how central the component is to the demo narrative and
   (b) how likely a reviewer is to poke at it.
4. **Flag what runs in CI vs. what only runs by hand.** A capability outside the gradle suite is one that
   can silently rot — this is already logged as open debt (05 item 5 on the old board).

## Deliverable

- A coverage map (component × test type × in-CI?) — this is the slide.
- A ranked gap list that becomes the work plan for briefs 03 and 04.
- A short honest statement of the *current* testing posture, suitable for the talk: strong and
  machine-verified at the engine/consensus layer; thinner at the inherited-service and cross-service
  layers; several capabilities proven by falsifiable scripts that should move into CI.

## Traps

- **Don't confuse "a proof exists" with "CI runs it."** Both matter; report them separately.
- Suites must be **run one at a time** — concurrent gradle builds produce an Aeron
  `RegistrationException` in `ThreeMemberClusterTest` and a timing miss in
  `SnapshotBarrierPerformanceTest`. `allocationGateTest` flakes at exactly 72 bytes (documented C2
  artifact) and is clean on rerun. Neither is a real failure; don't record them as coverage gaps.
- Each branch has its own baseline count — compare like for like, not YU13's number against YU15's.

## Conventions

Documentation-only; no GKE needed. Commit the map; `git push` goes to yaakov.
