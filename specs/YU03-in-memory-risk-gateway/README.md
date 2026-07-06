# YU03-in-memory-risk-gateway

Pre-trade risk admission tier for the LMAX BLP, forward-ported from the pre-k8s
`in-memory-risk-gateway` branch onto the `YU02-lmax-kubernetes` runtime as a spec-kit state delta.

- **Parent state:** `YU02-lmax-kubernetes`
- **Design baseline:** two-tier admission (ADR-018: Gateway replica screening + authoritative
  deterministic BLP decision/reservation), control events in the global journal (ADR-020),
  SEC Rule 15c3-5 control baseline. Original spec pack: `specs/in-memory-risk-gateway/` on the
  `in-memory-risk-gateway` branch.
- **Read first:** `spec.md` (scope + forward-port adaptations), `requirements/*.md`
  (per-requirement status), `generation/implementation-status.md` (what is done vs deferred).

Generate:

```bash
bash pipeline/generate-state.sh YU03-in-memory-risk-gateway
(cd generated/code/target-generated/order-matcher && ./gradlew test)
```

Everything in slice 1 is order-matcher runtime overrides; runtime/deploy harness is inherited
from `YU02-lmax-kubernetes` unchanged.
