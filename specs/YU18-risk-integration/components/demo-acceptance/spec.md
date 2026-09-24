# Demo acceptance specification

Updated: 2026-09-24. Status: review needed. Owner: Claude RI-07.

## Scope

A person other than the author should be able to bring up the full local rig from the guide,
confirm that it works, show every order type and the supported trade-to-risk path, collect
named evidence and remove only their own resources. The component reuses the order-types
(FR-OT), risk-pipeline (FR-RP) and EOD contracts; it defines no order or financial semantics.

## Requirements

- FR-DA01 **Own rig.** Every kind script accepts `KIND_CLUSTER_NAME`, and the default stays the
  historical name. A lane can create and delete its own cluster without touching a retained one.
  The image a pod runs is the image the bring-up loaded and named: the kind load list is derived
  from the pinned render. `RIG_OFFLINE=1` performs no bucket reads.
- FR-DA02 **Complete rig.** The trading tier, observability, frontend (edge proxy, directory,
  API docs) and console come up in a documented order. Every static edge-proxy upstream exists,
  and the NATS websocket that the console's live prices and blotter use is reachable.
- FR-DA03 **Readiness is function.** `rig-ready.sh` passes only if all of these hold:
  - every member applies the probe's own orders, with exactly one leader;
  - the gateway is ready;
  - the isolated probe books its legs into the read model;
  - the console reaches the gateway through its proxy;
  - the algo engine is ready;
  - Prometheus scrapes all members;
  - a deliberately rejected order's trace is fetchable from Tempo;
  - quotes exist.

  Each failure is named. A parse or tool failure fails the check instead of being skipped.
- FR-DA04 **Isolated startup probe.** The probe trades only a per-run minted instrument
  (`^PRB[0-9]+$`) between accounts from the reserved range 880000–889999. It refuses before
  trading when:
  - the instrument's book is non-empty on any member;
  - the read model already holds rows on it;
  - its accounts cannot be upserted.

  An existing account in the probe range is used only when it carries the automated probe name
  and has no user mappings; any other record is refused and never overwritten. `/seed` names
  only the probe instrument. After trading, the probe requires exactly 4 probe legs, 0 foreign
  legs, 0 open probe orders and flat probe positions. It also requires an unchanged sha256 over
  every complete non-probe order and trade row, streamed in primary-key order with no truncating
  aggregate. Every read-model query must succeed; a failed query is a refusal or a failure,
  never an empty answer. The window between the empty-book check and the first
  order is stated as residual, detected afterwards and not prevented.
- FR-DA05 **Order behaviours through the console path.** `order-types-live.py` submits through
  the console server's `/order-matcher` proxy. It judges each case at the read model and member
  `/bbo`, never by the HTTP answer alone. It covers the seven types, eligible TIFs, boundary and
  engine refusals, triggers, iceberg, peg reprice and suspension, cancel, replace and DAY expiry.
  A reported defect is tracked as a named known gap that still runs and reports GAP-CLOSED
  when it stops reproducing. Only exit 0 is acceptance: an open known gap exits 3
  (INCOMPLETE); a mismatch, or a failed or malformed read, exits 1 and aborts the run. No verdict
  is drawn from a read that did not happen.
- FR-DA06 **Trade to risk, honestly scoped.** `risk-flow-local.sh` runs the accepted RI-03 path
  on synthetic inputs with the assumed curve. It shows that any other bundle is refused before
  staging and names the mechanism. The profile restriction (only bundle `c3211337…`) is stated
  in the guide.
- FR-DA07 **Honest status presentation.**
  - The console header badge reflects the gateway's `/ready`, not "any HTTP answer".
  - A trailing stop's level is labelled as of the last update.
  - A live order's reason is labelled as the last refused change.
  - A missing limit shows `—`, not `0`.

## Acceptance evidence (2026-09-24, local-live unless stated)

| Req | Evidence |
|---|---|
| FR-DA01 | Rig `traderx-ri07-demo` created and deleted; the retained `traderx-yu12-cluster` stayed Exited. The derivation was dry-run against the default and override environments. |
| FR-DA02 | `start-frontend-kind.sh` first crash-looped the proxy on `tempo`, then on `api-explorer`, then passed. The live price chip was empty before the NATS alias fix and showed a live price after. |
| FR-DA03 | 16/16, twice, on `e96bf28a`. The negative controls earlier failed exactly the 5 targeted checks. |
| FR-DA04 | The old section 4, run verbatim, filled 1 of a user's resting 100-share IBM buy (`repro-old-probe-v2.txt`). Corrected probe, live on `e96bf28a`: user orders were byte-identical; a person's account in the probe range, a real ticker and a foreign resting order were refused (exit 2) and the account was left unchanged; a dead read model failed (exit 1). Source-level controls against a real MariaDB with the shipped DDL (16/16): the old GROUP_CONCAT digest is blind to a change beyond the 1 MiB cap and the new digest sees it; failed or partial queries fail; ownership conflicts are refused unmodified. |
| FR-DA05 | On `e96bf28a` (integrated F1 fix, no local patch), two runs each matched 30/30 ordinary cases, with F3 still present, so each exited 3 (INCOMPLETE). A stale business date exits 2. Thirteen source-level controls cover failed and malformed reads and the verdict codes; reverting either guard fails them. (Earlier runs used a scratch-patched gateway and are superseded.) |
| FR-DA06 | Three runs: CONTAINER_PRICING_VALIDATED; non-profile bundle FAILED with staging unchanged; mechanism asserted. Synthetic inputs, not financial validation. |
| FR-DA05 (F3) | On the F3 branch (`41523224`, with a scratch fix of the invalid C1 ConfigMap on the disposable rig only), two runs matched 31/31 with the trailing ratchet as an ordinary case: VERDICT PASS, exit 0. |
| FR-DA07 | Built console checked in the browser against the live rig; 97/97 Angular and 13/13 Node tests (source). |
