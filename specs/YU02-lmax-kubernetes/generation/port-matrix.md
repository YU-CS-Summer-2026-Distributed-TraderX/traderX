# 009b -> YU02-lmax-kubernetes Port Matrix

This matrix is the architecture-first forward-port map for carrying the `009b-lmax-sequencer-architecture`
runtime deltas onto the `014-fdc3-intent-interoperability` Kubernetes baseline.

Use it to answer three questions before any code move:

1. What is the durable `009b` behavior we are preserving?
2. Where does that behavior land in the `014`-derived generated/runtime surface?
3. What must be redesigned for Kubernetes instead of copied from the old compose runtime?

## Mapping Rules

- Keep `014` as the single lineage parent and preserve its frontend/FDC3/Sail behavior unless a row below says otherwise.
- Port durable behavior into `specs/YU02-lmax-kubernetes/generation/runtime-overrides/...`, not into generated output alone.
- Treat `specs/009b-lmax-sequencer-architecture/generation/runtime-overrides/order-management-matcher/docker-compose.yml`
  as a topology reference only. It is not a direct source of truth for Kubernetes manifests.
- When a row affects runtime startup or storage, update both Kubernetes bases:
  `generated/code/target-generated/kubernetes-runtime/manifests/base/` and
  `generated/code/target-generated/tilt-kubernetes-dev-loop/manifests/base/`.

## Port Matrix

| Area | `009b` donor source | `014`/generated target surface | Port action | Notes and delta shape |
| --- | --- | --- | --- | --- |
| Hot-path engine | `specs/009b-lmax-sequencer-architecture/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/lmax/*` | Durable target: `specs/YU02-lmax-kubernetes/generation/runtime-overrides/order-matcher/src/main/java/finos/traderx/ordermatcher/lmax/*` Generated landing: `generated/code/target-generated/order-matcher/...` | Carry forward | This is the core LMAX port: sequencer, rings, BLP, journaler, projector, output bridge, metrics. |
| Order matcher edge/service layer | `.../order-matcher/src/main/java/finos/traderx/ordermatcher/controller/OrderController.java`, `MarketTradeController.java`, `service/OrderMatcherService.java`, `config/PubSubConfig.java`, `OrderMatcherApplication.java`, `messaging/nats/NatsJSONPublisher.java` | Durable target: `specs/YU02-lmax-kubernetes/generation/runtime-overrides/order-matcher/...` Generated landing: `generated/code/target-generated/order-matcher/...` | Carry forward and reconcile with 014 baseline | Preserves service identity while replacing inherited matcher internals with sequenced gateway entrypoints and output publishing behavior. |
| Order matcher runtime config | `.../order-matcher/src/main/resources/application.properties`, `build.gradle` | Durable target: `specs/YU02-lmax-kubernetes/generation/runtime-overrides/order-matcher/...` Generated landing: `generated/code/target-generated/order-matcher/...` | Carry forward with Kubernetes env redesign | Must preserve LMAX config keys such as ring sizing, journal path, projector queue, gateway ack timeout. Replace 009/014 postgres assumptions with the chosen Kubernetes DB/storage plan. |
| Trade-service Gateway role | `specs/009b-lmax-sequencer-architecture/generation/runtime-overrides/trade-service/src/main/java/finos/traderx/tradeservice/controller/TradeOrderController.java` | Durable target: `specs/YU02-lmax-kubernetes/generation/runtime-overrides/trade-service/src/main/java/finos/traderx/tradeservice/controller/TradeOrderController.java` Generated landing: `generated/code/target-generated/trade-service/...` | Carry forward | `014` still publishes `/trades`; `009b` forwards validated tickets to `order-matcher /trades`. Preserve the unchanged external REST contract while changing the backend route. |
| Trade processor role | `specs/009b-lmax-sequencer-architecture/generation/runtime-overrides/trade-processor/src/main/resources/application.properties` | Durable target: `specs/YU02-lmax-kubernetes/generation/runtime-overrides/trade-processor/...` Generated landing: `generated/code/target-generated/trade-processor/...` | Keep deployed, reassess runtime role | In `009b` the service remains present but is no longer on the create-trade hot path. We should keep that boundary explicit on 014 and avoid deleting the service prematurely. |
| Supporting DB/client services | `specs/009b-lmax-sequencer-architecture/generation/runtime-overrides/account-service/...`, `position-service/...` | Durable target: `specs/YU02-lmax-kubernetes/generation/runtime-overrides/account-service/...`, `position-service/...` | Selective carry forward | These rows now stay aligned to the inherited `014` Postgres baseline. Port only the pieces needed for LMAX-path compatibility; do not revive the old MariaDB experiment. |
| Kubernetes order-matcher deployment | Runtime intent from `009b` `system/runtime-topology.md` and `order-matcher` config | Durable target: `specs/YU02-lmax-kubernetes/generation/runtime-overrides/kubernetes-runtime/manifests/base/order-matcher-deployment.yaml` plus Tilt twin | Redesign for Kubernetes | `014` deployment is stateless/basic today. Add startup/readiness gating for snapshot load, journal replay, warm-up replay, env for LMAX keys, and storage mounts for journal/snapshot/checkpoint assets. |
| Kubernetes trade-service deployment | Runtime intent from `009b` Gateway role and `TradeOrderController` override | Durable target: `specs/YU02-lmax-kubernetes/generation/runtime-overrides/kubernetes-runtime/manifests/base/trade-service-deployment.yaml` plus Tilt twin | Reconfigure | Preserve service identity/port, but point the service at the new Gateway flow and make sure env/config no longer assumes the old `/trades` publish-only path. |
| Kubernetes database deployment/init | Inherited `014` Postgres deployment/init manifests | Durable target: inherited `generated/code/target-generated/kubernetes-runtime/manifests/base/database-*.yaml` and Tilt twins unless an explicit Postgres change is required later | Keep inherited | Postgres is the final baseline. The LMAX port adapts to this DB shape rather than reintroducing MariaDB-specific manifests. |
| Stateful storage semantics | `009b` docs: journal dir, snapshot dir, projection checkpoint | Durable target: `specs/YU02-lmax-kubernetes/system/runtime-topology.md`, `requirements/nonfunctional-delta.md`, and Kubernetes manifest overrides | New Kubernetes design | Define PVCs or equivalent storage for journal/snapshot/checkpoint paths. This is not present as a durable manifest implementation in 009b because the original runtime was compose-oriented. |
| Observability and readiness | `009b` hot-path metrics + replay readiness semantics from `implementation-status.md` and `runtime-topology.md` | Durable target: `specs/YU02-lmax-kubernetes/generation/runtime-overrides/order-matcher/...` and manifest probes under Kubernetes/Tilt bases | Carry forward and expose | Keep LMAX metric families and ensure Kubernetes probes do not mark `order-matcher` healthy before replay/warm-up completes. |
| Price tick routing | `009b` runtime topology: price ticks reach Gateway for sequencing | Durable target: `specs/YU02-lmax-kubernetes/contracts/contract-delta.md`, `system/runtime-topology.md`, service/runtime overrides | Carry forward | `014` backend network shape otherwise stays intact; the meaningful backend route change is that price input must be treated as sequenced ingress, not out-of-band matcher subscription. |
| Frontend and FDC3 invariants | `014` spec pack and Sail overrides under `specs/014-fdc3-intent-interoperability/generation/sail-overrides/*` | Preserve in parent-generated surfaces: `generated/code/target-generated/web-front-end/angular/...`, `fdc3-intent-interoperability/...` | Keep inherited | No 009b donor material should overwrite these assets unless the LMAX port later introduces an explicit UI/interop requirement. |

## Compose-Only Assumptions To Replace

- `docker-compose.yml` service wiring from `009b` is reference material, not the target implementation.
- Local bind-mounted journal/snapshot paths must become Kubernetes storage definitions.
- Compose start-order assumptions must become probe-based readiness and dependency sequencing.
- Any localhost-style runtime addressing in old scripts/docs must be translated into service DNS or explicit ingress behavior.

## Initial 014 Delta Map

- `014` already gives us Kubernetes manifest scaffolding and the Sail/FDC3/UI continuation.
- `014` does not yet express LMAX stateful startup, warm-up gating, or journal/snapshot storage.
- `014` generated `order-matcher` and `trade-service` still reflect the older backend behavior, so those services are the first code-forward-port targets.
- `014` manifest bases already contain the concrete files we need to override first:
  - `generated/code/target-generated/kubernetes-runtime/manifests/base/order-matcher-deployment.yaml`
  - `generated/code/target-generated/kubernetes-runtime/manifests/base/trade-service-deployment.yaml`
  - `generated/code/target-generated/kubernetes-runtime/manifests/base/database-deployment.yaml`
  - `generated/code/target-generated/tilt-kubernetes-dev-loop/manifests/base/order-matcher-deployment.yaml`
  - `generated/code/target-generated/tilt-kubernetes-dev-loop/manifests/base/trade-service-deployment.yaml`
  - `generated/code/target-generated/tilt-kubernetes-dev-loop/manifests/base/database-deployment.yaml`

## Recommended First Port Order

1. Port `order-matcher` code/config overrides into `specs/YU02-lmax-kubernetes/generation/runtime-overrides/order-matcher/`.
2. Port the `trade-service` Gateway override.
3. Add Kubernetes/Tilt manifest overrides for `order-matcher` readiness, env, and storage.
4. Reconcile `trade-processor` and `position-service` surfaces once the main hot path is running on the Postgres-backed 014 base.
5. Add live runtime smoke for replay-gated readiness and projector writes against Postgres.
