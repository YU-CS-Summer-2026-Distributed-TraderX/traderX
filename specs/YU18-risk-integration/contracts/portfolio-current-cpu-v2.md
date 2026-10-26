# Current CPU synthetic portfolio contract (v2)

Engine: Alex's upstream `f9cc4c16fa843859729a9fb0956dd8cdfd2fa09b`, unmodified. Profile:
`current-cpu-v2`. Execution contract: `portfolio-runs-v1@f9cc4c16fa843859729a9fb0956dd8cdfd2fa09b`.
Supersedes [v1](portfolio-current-cpu-v1.md), whose owned service profile (`270a907a` on `c3d7c6a`)
is retired: upstream now provides the durable, content-bound submission identity it added.

## Request

Each file is an `engine.api.requests.RunRequestSchema` body, posted byte for byte:
`market`, `portfolio` (the positions), `analytics` (`["npv"]`, or `["npv", "exposure"]` with
`config.simulation`, `config.models` and `outputs.npv_cube = "inline"`), `config.base_currency`
`USD`, `idempotency_key` and `keep: true`. The key is the submission identity: the corpus
manifest's `submission_id`, or the run ID of a mapped TraderX export; a caller's identity can
only confirm it. Economics and bounds are unchanged from v1: the restricted USD bill/note
profile of [portfolio-generator-v1](portfolio-generator-v1.md).

## Transport and recovery

`POST /runs` once per consumer attempt; `202` names the `run_id`. `GET /runs/{run_id}` polls
until `done`, `failed`, `interrupted` or `cancelled`; terminal failures are never resubmitted.
`404` on POST means an engine without `/runs` (nothing queued); `409` means the key holds another
body (no run created); `400/413/422/429/503` are refusals before queueing.

Recovery after an uncertain submission resends the same bytes. The queue answers a known key
with the run it first created, comparing the body by its canonical form with defaults filled in
(`RunRequestSchema.body_sha256`), so formatting, equal numbers and spelled-out defaults are the
same body and another body is a `409`. `keep` exempts the run from the queue's retention purge,
so a resend never finds the key forgotten. The SQLite queue is host-local: recovery is to the
same queue.

A run polled by ID (no POST) is bound to the retained request by the result's own echo of what
it ran: `analytics`, `asof`, `base_currency` and every requested `config` field, so a changed
seed or sample count is refused. Prices and economics are bound by the independent oracle.

## Result and intake

Rows are `trades[]` with `trade_id` in request order, `product` and `npv`; `npv` is their sum.
Intake checks coverage and order, independent restricted cashflow NPVs, the sum, the float64
configuration as run with no per-product or per-trade override, no warnings, and no analytic
beyond the request. An npv-only run has no device report. An exposure run must also report CPU
devices, the requested path count and float64 policy; its inline cube is `[scenario, date,
trade]` over the three future dates, and its profiles prepend t=0 with EPE/ENE matching the
independent prices. Original request/reply bytes, the result and a create-only acceptance
manifest are retained; offline reads recheck hashes, the `POST /runs` 202 and the completed
`GET /runs/{run_id}` witnesses, and the request binding. Hashes detect accidental corruption,
not hostile rewriting by the evidence owner.

No account authorization, sequenced export lineage, production authentication or general
financial acceptance is claimed. Reproduce with [the local CPU guide](../../../docs/risk-integration/local-cpu-loop.md).
