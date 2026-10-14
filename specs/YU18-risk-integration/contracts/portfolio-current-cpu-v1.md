# Current CPU synthetic portfolio contract

Numerical revision: `c3d7c6ae485213dbb24abd2724f9ae8cb85acbb8`. Profile:
`current-cpu-v1`. Execution contract:
`portfolio-local-cpu-v2@c3d7c6ae485213dbb24abd2724f9ae8cb85acbb8`.

The v2 corpus sidecar binds profile, numerical revision, seed/settings, portfolio,
submission and trade identities, exact request hashes/lengths and economics
counts. Profile participates in corpus identity. Current requests use
`engine.api.requests.MarketPortfolioRequestSchema`, with no retired
`schema_version` field. Legacy v1 manifests and their exact fixture remain valid
only for their original profile; neither contract is silently reinterpreted.

Economics and bounds remain the restricted USD bill/note profile of
[portfolio-generator-v1](portfolio-generator-v1.md): signed face, dirty USD NPV,
explicit regular semiannual ACT/ACT(ICMA) schedules, assumed flat continuous
ACT/365 zero curve, no settlement adjustment or observations. For current
exposure requests, the raw cube lower bound is paths × 3 future dates × trades ×
8 bytes; profiles additionally contain t=0. Intermediates and serialization cost
more. No nonlinear portfolio risk is produced by partitioning and summing jobs.

GET capabilities must match the pinned execution/numerical identity before POST.
POST `/portfolio/price` uses the manifest submission key and happens once per
consumer attempt. GET `/portfolio/submissions/{id}` binds the returned job;
GET `/jobs/{id}` carries its persisted contract/kind. Unknown or interrupted work
is not automatically resubmitted. A failed worker launch must retain durable
accepted identity. The host-local SQLite queue is not shared across machines.

### Complete request identity for recovery

The v2 transport exposes `request_content_sha256` from the persisted job on
admission, submission lookup and every job state. The consumer recomputes it from
its retained request and requires equality before accepting recovered or explicit
jobs. Offline acceptance checks the digest against both retained HTTP witnesses.
Changing a simulation seed or sample count must refuse with zero POSTs and no
new acceptance record, even when every base NPV would agree.

Algorithm `json-typed-decimal-sha256-v1` hashes compact sorted-key JSON containing
`kind`, `execution_contract` and recursively tagged `request`. Null is `["null"]`,
boolean `["boolean", value]`, string `["string", value]`, array `["array", items]`,
object `["object", sorted_key_and_tagged_value_pairs]`, number `["number", text]`.
Fractional JSON numbers use exact Decimal parsing; integers are exact. Zero is
`0`; other numbers use signed coefficient digits with trailing zeros removed,
then `e` and the adjusted decimal exponent. Encoding uses JSON ASCII escaping and
UTF-8 bytes. No float conversion/context rounding occurs; nonfinite numbers are
rejected. All nodes are tagged to avoid type collisions.

Formatting, object order and equal numeric representations compare equally;
booleans differ from numbers and distinct decimal values remain distinct. Missing
defaults are conservatively different content. The service separately retains
Pydantic/default-expanded idempotent admission, so explicit/omitted defaults can
return the same job without being mislabeled as the original content snapshot.
Current explicit-job polling also requires submission identity context. Old v1
service contracts cannot satisfy the current consumer or be repriced by the new
worker. This does not change the legacy generator or EOD profiles.

Current results carry `trades` rows keyed by trade ID in request order, rather
than parallel ID/value arrays. Intake checks exact coverage/order, independent
restricted cashflow NPVs, portfolio sum, calculation availability, CPU/float64
precision and warnings, plus exposure grids/shape/finite values when requested.
Original request/reply bytes, result document and create-only acceptance manifest
are retained. Offline reads recheck hashes, submission/job/contract witnesses and
numerical assertions. Hashes detect accidental corruption, not hostile rewriting
of all private evidence by its owner.

No account authorization, sequenced export lineage, production authentication or
general financial acceptance is claimed. Reproduce with
[the local CPU guide](../../../docs/risk-integration/local-cpu-loop.md).
