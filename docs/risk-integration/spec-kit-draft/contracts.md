# Boundary contracts — proposed rules

## Authority and schemas

Reuse and pin the engine's published result/capability schemas after review. Do not invent competing JSON shapes in this draft. At reviewed e4ca50b the identifiers are `jaxrisk.eod-result.v1` and `jaxrisk.eod-capabilities.v1`. Producer formats belong to profiles such as [TraderX](traderx-profile.md).

A release manifest must pin the shared spec revision, schema bytes/hash, engine build identity, mapping version and accepted producer profiles. Breaking changes require a new version and migration tests. Additive-field policy must be explicit; do not assume our existing exact-shape validator accepts additions.

## Identity and submission

- Workload identity covers verified input content, resolved market inputs, requested calculations, reporting currency, numerical settings and relevant engine/mapping/schema versions.
- A bundle ID is sufficient for content identity only if its relationship to all relevant bytes is verified. A caller-supplied identifier alone is insufficient.
- Submission ID identifies a request; attempt ID identifies an execution. Neither replaces workload identity.
- Validate submission-ID binding before cache lookup. A cache hit for a new ID must record a recoverable binding under the agreed policy.
- `reuseExistingResult=false` may request another attempt under a new submission ID; it must not bypass same-ID retry protection.

## Lifecycle

Proposed conceptual states: accepted/running/completed/failed, plus unknown lookup. Wire names and recovery states require agreement in D-03.

Completed and failed attempts are immutable and discoverable after restart. A completed result must remain discoverable if pointer publication fails. A later failure must not mask an earlier successful result for the same workload. Active attempts need execution ownership; returning a shared object to two handlers is insufficient.

Recovery must distinguish a lost execution from a result that was never submitted. For the first single-worker deployment, agree a boot/lease or explicit interrupted-attempt policy. Do not claim distributed exactly-once semantics from a process-local lock or filesystem rename.

## Options, errors and coverage

Unsupported reporting currency is rejected before execution. A supported currency must be reflected in monetary results and conversion provenance. Requested calculations are either honored or explicitly refused according to advertised capabilities. Unknown option values cannot produce successful default behavior.

Existing EOD transport uses 409 for submission conflict, 422 for unusable bundle/terms, 400 for unresolved market inputs, and 200 with per-item outcomes for supported requests containing refused items. Preserve these distinctions or version the change. Exact error codes for request options remain D-02.

## Transport and persistence

Keep bundle delivery, request submission, lookup and result intake as separate adapter operations. Local `bundlePath` is a server-side path, not an upload protocol. A future remote profile must define staging, byte verification, authentication, authorization, size limits and path/object access boundaries before exposure.

Do not infer a remote service deployment or storage rights from this draft. Current initial probes use TestClient and temporary stores only.
