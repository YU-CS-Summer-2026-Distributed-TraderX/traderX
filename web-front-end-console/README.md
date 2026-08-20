# TraderX console

The first UI that shows what this system actually is: five instrument classes, an Aeron cluster
whose three members you can watch agree, rejections with reason codes, live latency, and the EOD
session chain. Runs **alongside** `web-front-end-angular` (the upstream `:state009` app), which it
does not replace.

Deliberately standalone — **not** folded into `specs/` (layer composition for an app with no
lineage is the expensive order; promote it later if it earns it).

## Running

```bash
npm start
```

That is the whole workflow: the dev proxy (`proxy.conf.mjs`) spawns and babysits the
`kubectl port-forward svc/edge-proxy` itself, reads the rig's dev-token master secret the same way
the proof scripts do (so the EOD panel auto-mints its admin token — no pasting), and proxies the
`/nats-ws` websocket for the live blotter. Override the rig with `RIG_CONTEXT` / `RIG_NAMESPACE`
env vars if it isn't `kind-traderx-yu12-cluster` / `traderx`.

## Pages

- **Trading** — order entry (six tickets: equity, OCC option with symbol-derived terms, treasury
  and corporate at fraction-of-par with the day-count split from the reference-data join, swap and
  swaption as terms with no NPV by design; equities additionally offer TWAP/VWAP execution — the
  parent goes to the algo engine, which slices children through the same consensus path), blotter
  & positions, activity & rejections with reason codes. A **Demo preset** dropdown fills the whole ticket for each story — Submit is the only
  remaining click. The blotter subscribes `/accounts/{id}/trades|positions` on the message bus and
  shows `live · message bus` when the feed is up, `polling` otherwise. Each blotter subsection
  collapses, pages ten rows at a time (the page size is editable), and **Find by reference** jumps
  straight to the page holding an order, trade or contract id and highlights it. Position totals
  span every position, not the visible page.
- **System** — the three cluster members side by side (role, applied, engineApplied, trades) with
  an agreement banner, live latency/throughput with consensus p50/p99 (sample count shown —
  a percentile without its n is an anecdote), and **service status**: one browser request per
  service through the edge proxy, so green means the path this console depends on works end to
  end. **EOD cut provenance** lists every cut on the rig's own sink — the committed `.cut` plus the
  two artifacts rebuilt from it (netted positions, and OTC contracts at contract grain), each with
  the SHA-256 taken on the pod. Both derived artifacts name the cut they came from, so the panel
  checks that claim on screen rather than asserting it. The GCS archive is listed alongside as a
  second source (older, uploaded cuts, no contracts artifact — it predates YU17).
- **End of day** — draft vs published with the version chain (a correction is a new version,
  ADR-026), per-instrument quality codes, the override form, and the publish button that shows the
  quality gate's 409.

- **Admin** — a **live trading session** (several accounts submitting real orders at their own
  rate for their own duration, stoppable, so every other surface has something to show at once),
  trade lifecycle (T+n settlement with force-settle, inline TCA reports), algo parent orders with
  their bucket schedules (renders "engine scaled to 0" legibly — the proof suite parks it),
  cancel-by-orderRef, recon status + orphan sweep.
- **Accounts** — create a trading account and admit it to the engine's risk state, or suspend one.
  Both halves are shown because both are needed: the account service is the directory, and the
  engine keeps its own risk state, so an account that exists in one and not the other lists
  normally and rejects every order (`UNKNOWN_ACCOUNT`, or `ACCOUNT_DISABLED` once suspended).
  Admission is a control command sequenced through consensus like an order.
- **Kdb** — the KDB-X capture tap: per-member tickerplant logs (leader-side, so rows map to
  leadership windows), q-style VWAP computed from txtrade, and the latest captured trades. Served
  by a read-only dev-proxy bridge (kubectl exec tail).

Order details: click an activity entry to see orderRef, clientOrderId and the order's **trace** —
the trace id is derived client-side with the same FNV/mix math as OrderTrace.java (verified
byte-equal against a gateway reject log line), fetched from Tempo via the `/tempo/` route, and
rendered as a span timeline. Rejects are always head-sampled, so a refusal's trace always exists.
Blotter trade rows expand to details with inline TCA and force-settle.

Every panel carries a `?` hover explainer written for someone who doesn't know the system.

## Writing a panel: `computed()` only sees signals

**Any `computed()` in these panels that reads a plain class field is silently wrong.** It will
render correctly once and then never update, because a computed recomputes only when a *signal* it
read changes — a plain field is invisible to it. Nothing fails: no error, no warning, just a value
frozen at first render, which reads as a backend problem and gets debugged as one.

This has cost three sittings here already — the ticket's bond terms stopped following the selected
instrument, the blotter kept showing the previous account's OTC contracts after a switch, and the
paged sections would have frozen on their first page of rows. It is a property of this codebase,
not an accident. So: if a value feeds a `computed()`, make it a `signal()`, and bind it as
`[ngModel]="x()" (ngModelChange)="x.set($event)"` rather than `[(ngModel)]="x"`.

The reverse trap is just as cheap to hit: converting a field to a signal makes every *other* read
of it a read of the function object. Grep every use before flipping one — a loosely-typed request
body will happily serialize a function and the build will pass.

## If listed options reject with UNKNOWN_SECURITY

The instrument is fine and the message is misleading: `/resolve` succeeds and the publisher marks
the contract, but the security is not *enabled* in the engine's risk state for the current epoch.
A fresh epoch re-seeds equities and bonds only, so an epoch roll silently drops the whole option
class (filed: `issues/open/an-epoch-roll-silently-drops-instrument-classes.md`). Re-enable the
packaged chain — 2 underlyings x 2 expiries x 3 strikes x call/put — with:

```bash
MATCHER_URL=http://localhost:30080/order-matcher bash scripts/proofs/seed-option-chain.sh
```

## If option positions vanish after a proof-suite run

Expected, and not a UI fault: `scripts/proofs/yu15-option-persistence.sh` deletes every trade and
position whose security is longer than 15 characters — which is every OCC symbol — and its restore
step puts back only the `stocks` catalog rows. The contracts stay tradeable (enablement lives in
cluster state, not the database), so the blotter simply loses its option history. Recovery is the
two option presets, in order: **1/2** rests an offer from the counterparty account, **2/2** lifts
it — a real print and a position on both sides.

## Known gaps (deliberate)

- **The NATS websocket listener is enabled on the kind rig (2026-08-19)** and declared in the YU17
  cluster layer's `nats.yaml`; on any other rig the blotter pill honestly says `polling` until the
  same is applied (NATS pod restart — JetStream state on emptyDir does not survive it).
- **p50/p99 latency needs `LATENCY_DECOMP=1` on the gateway** — live on the rig and declared in
  the YU17 layer since 2026-08-18.
- **EOD cut provenance still has no HTTP surface** — the extract writes files to
  `file:///data/risk-extracts` on the risk-extract pod, so the panel reads them through a read-only
  dev-proxy bridge (`kubectl exec`, same shape as the kdb one). A deployed console would need a
  real read endpoint. Note the sink is where cuts taken *on this rig* land; the GCS bucket holds
  older uploaded ones.
- The in-cluster deployment (Dockerfile + `/console/` edge route) is still deferred; the auto-mint
  and port-forward conveniences above are dev-proxy-only and would need real equivalents.
