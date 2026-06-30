# Runtime Topology: lmax-kubernetes

Parent state: `014-fdc3-intent-interoperability`

Describe runtime topology and network/data flow changes introduced by this state.

## Entrypoints

- TraderX browser/UI/API entrypoint remains inherited from `014`.
- Existing websocket and FDC3 sidecar entrypoints remain inherited from `014` unless changed later.
- LMAX matcher health/readiness endpoints use Spring actuator probes on the inherited `order-matcher` service:
  - liveness: `/actuator/health/liveness`
  - readiness: `/actuator/health/readiness`
  - readiness includes the custom `lmaxRecovery` contributor

## Components

- Inherits the Kubernetes/C3/FDC3 runtime components already present in `014`.
- Replaces inherited trading-path internals with the LMAX matcher, Gateway, replay, and output-ring semantics from `009b`.
- Introduces stateful runtime concerns for journal, snapshot, replay, and readiness management.
- Keeps Postgres as the durable database baseline inherited from `014`.
- Mounts a dedicated `order-matcher-lmax-data` PVC for journal and snapshot files under `/var/lib/traderx-lmax`.

## Networking

- Existing `014` frontend/FDC3 traffic remains inherited.
- Trading-path network changes are expected to mirror `009b`: sequenced input, output-bridge egress, and projector-only persistence writes.
- Persistence writes continue to flow only from the projector into Postgres.
- Failover and multi-node replication remain to be specified in follow-up implementation.

## Startup / Health Order

1. Generate and verify inherited `014` baseline assets.
2. Start Postgres, NATS, and inherited support services.
3. Start `order-matcher` with readiness forced to `REFUSING_TRAFFIC`.
4. Run LMAX recovery:
   - `recovery.source=db` warm-start from Postgres read models
   - optional journal replay verification if the journal is enabled
   - snapshot scheduler activation after recovery completes
5. Flip readiness to `ACCEPTING_TRAFFIC` only after matcher recovery completes successfully.
6. Start or verify `trade-service` as the Gateway edge that forwards trade tickets into `order-matcher`.
7. Revalidate inherited FDC3/Sail behavior after the trading-path port lands.

## Current Boundaries

- Recovery/readiness gating is implemented in this slice.
- JIT warm-up replay is still deferred; readiness is currently recovery-gated, not warm-up-gated.
- Snapshot files currently live beside the journal under the same mounted PVC because `SnapshotStore` writes into the journal directory.
- Projector checkpoint files are still deferred; the durable watermark is currently the Postgres-backed projector progress plus the journal/snapshot pair.
