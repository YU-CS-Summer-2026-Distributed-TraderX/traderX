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

- **Admin** — a **live trading session**: several accounts submitting real orders at their own rate
  for their own duration, stoppable, so every other surface has something to show at once. The
  instrument pool is a multi-select over the whole catalog plus free-typed extras (OCC contracts
  live in engine risk state, not reference data, so they are typed rather than listed); *random
  instrument per order* spreads flow across books, off keeps every actor in one book where they
  cross and print. Quantity `-1` draws a random multiple of 25 in [25, 10000]. **Batch ingress**
  switches each tick to one `POST /orders/batch` carrying an array — the gateway offers the whole
  array back to back without waiting for each ack and fences once at the end, which is the
  high-throughput path the load benches use; it answers with a count rather than per-order refs, so
  batched orders carry no client order id and no trace. Batch and order-at-a-time are mutually
  exclusive at the gateway (it drains pipelined singles when a batch starts), which is why the
  toggle is session-wide and not per actor.
- Also on **Admin**: **book bands & refusals** — a pre-demo screen, not a diagnostic. The price
  collar is a band anchored on the *first limit into a book*, not a percentage around the mark, so a
  book anchored by a stray order refuses every realistic price for the rest of the epoch and
  nothing repairs it in place (a seed cannot move a mark that has printed, and the band is not
  derived from the mark anyway). The screen compares accepted against refused prices per security
  from the regulatory journal: **disjoint ranges are the signature of a mis-anchored book; an
  overlap means the refusal came from something else and says nothing about the band.** That
  distinction is the whole value — this rig shows refusals on seven securities and exactly one is
  mis-anchored. The order ticket carries the same warning inline, so a doomed order is flagged
  before it is sent rather than explained afterwards.
- Also on **Admin**: trade lifecycle (T+n settlement with force-settle, inline TCA reports), algo parent orders with
  their bucket schedules (renders "engine scaled to 0" legibly — the proof suite parks it),
  cancel-by-orderRef, recon status + orphan sweep.
- **Accounts** — create a trading account and admit it to the engine's risk state, or suspend one.
  Both halves are shown because both are needed: the account service is the directory, and the
  engine keeps its own risk state, so an account that exists in one and not the other lists
  normally and rejects every order (`UNKNOWN_ACCOUNT`, or `ACCOUNT_DISABLED` once suspended).
  Admission is a control command sequenced through consensus like an order. New accounts take a
  random unused five-digit id, so the form asks only for a name. The applied-state column is kept in
  session storage: the engine has no read surface for account risk state, so without that a refresh
  would leave no record at all of which accounts had been suspended.
- **FIX** — the gateway's *second* ingress, and the only page here that leaves HTTP. A counterparty
  session (FIX 4.4, CLIENT1 → TRADERX) is held against the gateway's own acceptor port; the gateway
  terminates it itself and forwards each NewOrderSingle through the same submitter seam, consensus
  log and risk gate as a ticket order. The page shows the whole exchange as raw wire text with every
  tag decoded — Logon, NewOrderSingle, ExecutionReport — because the point is that it really is FIX,
  not a description of FIX. A browser cannot open a TCP socket, so the dev proxy holds the session
  for the length of one order; sessions are ephemeral by design (MemoryStoreFactory), so a
  one-order session is an ordinary one rather than a shortcut.
- **Kdb** — the KDB-X capture tap, with each view showing the `txstore.q` function it reproduces
  beside it: per-member capture files (leader-side, so rows map to leadership windows), fill VWAP
  by symbol (`.tx.fills`), order final state keyed `(epoch, ref)` (`.tx.orders` — never `ref` alone,
  since orderRef restarts at 1 on a fresh incarnation and a bare ref silently merges two orders),
  sequence gaps (`.tx.gaps`, framed as a question rather than a verdict — control events leave
  legitimate holes), and merged session playback (`.tx.session`). Served by a read-only dev-proxy
  bridge (`kubectl exec` tail, last 300 rows per file — which is why the gaps view has a floor).
- **Grafana** — the observability stack: all 18 provisioned dashboards deep-linked by uid, plus a
  health check that reads the response *body* (a dev server answers unknown paths with its SPA
  fallback at 200, so status alone would report a missing proxy route as a healthy Grafana). Opens
  in a new tab rather than embedding: anonymous access is off and Grafana refuses to be framed, and
  turning either on to decorate a console page is the wrong trade.
- **Legacy UI** — a link to the original FINOS TraderX app running against the same cluster, with
  what the YU17 override layer added to it and why. Four of those six are defects rather than
  features: the upstream app is correct against the single-BLP tier and wrong against the cluster
  tier, so the layer is a dialect translation, not a bug fix in the component.

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
- **MSFT is untradeable at a realistic price on this epoch** — every accepted MSFT order is at
  180.00, every order at 384+ is refused. It is the worked example behind the book-bands screen,
  and it cannot be fixed from the console. Check the screen before choosing what to demo.
- **A refusal's reason is not in the regulatory report.** `ORDER_REJECTED` carries
  accountId/orderId/security/side/quantity/price/seq/timestamp and no reason code, so the
  book-bands reading is inference from prices, labelled as such. The order path *does* return the
  reason — an order typed into the ticket names why it was refused.
- **The FIX page needs the dev proxy**, which port-forwards `svc/order-matcher:18130` and speaks the
  session on the browser's behalf. A deployed console would need a server-side FIX client of its
  own; nothing in the browser can open a TCP socket. Watch the timestamp format if you touch it:
  FIX UTCTimestamp is `YYYYMMDD-HH:MM:SS.sss`, and stripping the colons gets the whole Logon
  refused with *"Incorrect data format for value, field=52"*.
- ~~**Cuts on the kind rig are ephemeral**~~ — **fixed 2026-08-20.** `/data/risk-extracts` is a PVC
  (`risk-extract-extracts`, 1Gi RWO), so **cuts survive a reschedule of `risk-extract`**. Deliberately
  narrower than "cuts are durable": they do not survive the PVC itself being deleted, and none of it
  applies to GKE, whose overlay writes to `gs://` and was never affected. Verified from this
  console's own read path — nine artifacts, `delete pod -l app=risk-extract`, a genuinely different
  replacement pod, all nine shas byte-identical afterwards. The empty-sink banner is repointed
  rather than retired: an empty sink now means the **EOD chain has not produced a cut**, which is a
  real fault rather than a plumbing artefact.
- **Reading the sink is a kind-only gap, and an HTTP server is the wrong fix.** `risk-extract` has
  no Service and no container ports — it is a headless worker, NATS in, files out — so there is
  nothing for an edge-proxy route to point at, and serving the files would mean giving a
  deliberately-headless component an ingress. On GKE the gap does not exist: the sink is
  `gs://traderx-…-risk-extracts`, already a URL and already durable. So the work item is *deployed
  console reads the bucket, local console keeps the `kubectl exec` bridge* — not "build a file
  server into risk-extract".
- The in-cluster deployment (Dockerfile + `/console/` edge route) is still deferred; the auto-mint
  and port-forward conveniences above are dev-proxy-only and would need real equivalents.
