# Missed-event recovery plan

1. Reproduce dropped NATS events on disposable SQL/NATS/Aeron fixture and retain evidence.
2. Strengthen archive continuity checks and add stable-boundary recovery export using production serializers. Preserve migration export.
3. Add bounded local peer call, operator recovery service/controller and additive checkpoint migration. Preflight all historical identity/economics before writes; transaction covers source-order application, derived positions and checkpoint.
4. Add SQL rollback/retry/conflict/isolation controls and real service/transport outage fixture. Regenerate YU18 sequentially and verify last-wins parity.
5. Record executable proof/evidence hashes and honest limits; commit owned paths and hand off.

YU18 owns operative ClusterRecon, ClusterNodeMain, TradeService, registry and order projection overrides. Earlier same-basename layers remain unchanged. No state-root generation inputs are placed under components. No NATS configuration-only fix: Core NATS cannot replay publisher-side loss. Existing frozen migration witness requires a permanently frozen source, so the additive endpoint brackets replay with stable applied state and uses identical archive source.
