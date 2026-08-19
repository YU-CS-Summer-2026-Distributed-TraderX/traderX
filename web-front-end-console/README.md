# TraderX console

The first UI that shows what this system actually is: five instrument classes, an Aeron cluster
whose three members you can watch agree, rejections with reason codes, live latency, and the EOD
session chain. Runs **alongside** `web-front-end-angular` (the upstream `:state009` app), which it
does not replace.

Deliberately standalone — **not** folded into `specs/` (layer composition for an app with no
lineage is the expensive order; promote it later if it earns it).

## Surfaces

- **Aeron cluster** — the three members' role / `applied` / `engineApplied` / `trades`, side by
  side, polled every 2s, with an agreement banner. Reachable from a browser only via the
  `/m0../m2` edge-proxy routes (see below).
- **Order entry** — six tickets: equity, listed option (OCC symbol; underlying/expiry/call-put/
  strike are *derived from the symbol*, never entered), treasury and corporate (fraction-of-par
  price, six decimals, quantity = USD face; issuer/day-count/rating shown from the reference-data
  join), swap and swaptions (terms, not a price — no NPV exists in this system, by design).
- **Blotter & positions** — off position-service, per account.
- **Activity & rejections** — every submission with its outcome; rejects show the `RiskReason`
  code (`PRICE_COLLAR`, `UNKNOWN_SECURITY`, `PRICE_MISSING`, …).
- **Latency & throughput** — gateway `/metrics` (ack rate derived client-side) and `/latency`.
- **EOD session** — draft vs published with the version chain (a correction is a new version,
  ADR-026), per-instrument quality codes, the override form, and the publish button that shows
  the quality gate's 409 when a flagged session refuses to publish. Needs an admin JWT: paste the
  rig's dev-token master secret once, the panel mints its own token
  (`POST /trade-processor/auth/dev-token`).

## Running against a rig

The app calls same-origin relative paths and expects the edge-proxy route table. In dev:

```bash
kubectl --context kind-traderx-yu12-cluster -n traderx port-forward svc/edge-proxy 30080:8080 &
npm start          # ng serve with proxy.conf.json -> localhost:30080
```

`proxy.conf.json` forwards `/order-matcher`, `/reference-data`, `/account-service`,
`/position-service`, `/trade-processor`, `/m0../m2` to the port-forward.

The `/m0../m2` per-member health routes are served by the `edge-proxy-config` ConfigMap; the
operative manifest is
`specs/YU17-otc-rates/generation/runtime-overrides/kubernetes-runtime/manifests/base/edge-proxy-configmap.yaml`
(the YU17 layer is last, so it wins). Applied to the kind rig 2026-08-18.

## Known gaps (deliberate)

- **Swap/swaption booking needs a YU17 gateway.** The rig's `:yu16-ackB` build has no
  `/swaps`/`/swaptions` routes; the ticket reports "route absent" on 404. Roll the tier to a YU17
  ackB build (members + gateway together, fresh epoch — 24 vs 32-byte ack wire rule) to demo it.
- **EOD cut provenance** (consensus seq + per-member SHA) has no HTTP surface anywhere — the
  extract writes files. Needs a read-only endpoint before it can be a panel.
- Polling, not push. NATS later, once the shape settles.
- Styling is "legible", per the brief.
