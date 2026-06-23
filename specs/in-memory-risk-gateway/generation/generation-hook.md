# Generation Hook: in-memory-risk-gateway

- Hook: `pipeline/generate-state-in-memory-risk-gateway.sh`
- Feature pack: `specs/in-memory-risk-gateway`
- Parent state: `009b-lmax-sequencer-architecture`
- Overlay model: generate parent, then apply ordered state patchset/overrides

## Hook Responsibilities

1. Delegate direct invocation to `pipeline/generate-state.sh in-memory-risk-gateway`.
2. Generate parent `009b-lmax-sequencer-architecture` from a clean state.
3. Apply the ordered `in-memory-risk-gateway` overlay patchset.
4. Generate/compile submitted-command, control-event, and decision codecs before Java compilation.
5. Materialize account/reference/risk source outbox/snapshot changes and durable control-stream config.
6. Materialize Gateway replica/bootstrap/readiness/screening and BLP risk/reservation/idempotency code.
7. Extend snapshot/replay, no-GC, latency, observability, and failure-mode tests/assets.
8. Render architecture docs from `system/architecture.model.json`.
9. Preserve inherited `009b` output handlers, projector, benchmark tasks, external accepted payloads,
   UI journeys, LGTM stack, C2 build/publish assets, and deployment bundle.
10. Install state-native start/status/stop/test scripts and state UI metadata.
11. Produce deterministic generated output suitable for snapshot branch publication.

## Runtime Scripts

- `scripts/start-state-in-memory-risk-gateway-generated.sh`
- `scripts/status-state-in-memory-risk-gateway-generated.sh`
- `scripts/stop-state-in-memory-risk-gateway-generated.sh`
- `scripts/test-state-in-memory-risk-gateway.sh`

## Patch Capture

After implementation, capture the state delta against `009b`:

```bash
bash pipeline/create-state-patchset.sh \
  in-memory-risk-gateway \
  009b-lmax-sequencer-architecture
```

The executable hook and captured patchset are implemented. Runtime overrides remain the readable
source of truth; the patchset is the reproducible parent-to-child publication artifact.
