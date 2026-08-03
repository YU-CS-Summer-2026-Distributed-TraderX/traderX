# Functional Delta: YU02-lmax-kubernetes (vs 014-fdc3-intent-interoperability)

Everything in `014` is carried forward unless named below: the Kubernetes runtime, the C3/FDC3
intent and context flows, and the inherited service identities all apply unchanged. What this state
changes is the trading path underneath them — the matcher stops being a synchronous,
database-backed service and becomes an LMAX-style hot path, using the sequencer architecture proved
standalone in `YU01-lmax-sequencer`.

## Added

- FR-LK06 — startup performs snapshot load, journal replay and warm-up replay, and readiness is
  gated until replay completes, so a node never serves traffic from partial state.
- FR-LK07 — durable Kubernetes storage and lifecycle rules for journal, snapshot and checkpoint
  assets, making recovery a property of the deployment rather than of one pod's disk.
- FR-LK09 — a port matrix and implementation-status document are produced before any claim of
  runtime parity, so parity is asserted against a written contract rather than an impression.

## Changed

- FR-LK03 — inherited matcher-path internals are replaced by the sequencer and single-writer hot
  path forward-ported from `YU01-lmax-sequencer`; matching becomes in-memory and event-sourced, and
  the database becomes an asynchronously projected read model instead of the source of truth.
- FR-LK04 — `order-matcher` becomes the LMAX hot-path node under Kubernetes while keeping its
  inherited service identity, so callers are unaffected by the internal change.
- FR-LK05 — `trade-service` takes the gateway/receptionist role in front of the hot path.
- FR-LK02 — the Kubernetes, C3 and FDC3 runtime contracts inherited from `014` are preserved; a
  contract change has to be documented rather than absorbed silently.

## Removed

- The synchronous per-order database round trip on the admission path, which was the throughput and
  latency bottleneck this state exists to remove.

## Flow Impact

- FR-LK01 — publish lineage continues from `014-fdc3-intent-interoperability`; this state layers the
  LMAX path onto that runtime rather than branching away from it.
- FR-LK08 — inherited Sail and FDC3 assets from `014` coexist with the LMAX path, with any change
  stated explicitly.
