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
  swaption as terms with no NPV by design), blotter & positions, activity & rejections with reason
  codes. A **Demo preset** dropdown fills the whole ticket for each story — Submit is the only
  remaining click. The blotter subscribes `/accounts/{id}/trades|positions` on the message bus and
  shows `live · message bus` when the feed is up, `polling` otherwise.
- **System** — the three cluster members side by side (role, applied, engineApplied, trades) with
  an agreement banner, and live latency/throughput with consensus p50/p99 (sample count shown —
  a percentile without its n is an anecdote).
- **End of day** — draft vs published with the version chain (a correction is a new version,
  ADR-026), per-instrument quality codes, the override form, and the publish button that shows the
  quality gate's 409.

Every panel carries a `?` hover explainer written for someone who doesn't know the system.

## Known gaps (deliberate)

- **The live blotter needs the NATS websocket listener enabled on the rig** — declared in the YU17
  cluster layer's `nats.yaml` (ConfigMap-based config; nats-server cannot enable websocket from a
  CLI flag) but applying it restarts the NATS pod, and JetStream state on emptyDir does not
  survive that, so the apply is a human's call. Until then the blotter pill honestly says
  `polling`. The `nats-broker` Service also needs port 8081 added (the edge proxy's `/nats-ws`
  route targets it).
- **p50/p99 latency needs `LATENCY_DECOMP=1` on the gateway** — live on the rig and declared in
  the YU17 layer since 2026-08-18.
- **EOD cut provenance** (consensus seq + per-member SHA) has no HTTP surface anywhere — the
  extract writes files. Needs a read-only endpoint before it can be a panel.
- The in-cluster deployment (Dockerfile + `/console/` edge route) is still deferred; the auto-mint
  and port-forward conveniences above are dev-proxy-only and would need real equivalents.
