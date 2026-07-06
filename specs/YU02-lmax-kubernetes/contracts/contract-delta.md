# Contract Delta: YU02-lmax-kubernetes

Current contract stance:

- inherit external REST, websocket, NATS, and FDC3-facing contracts from `014` unless a ported `009b`
  behavior explicitly requires a documented change
- treat internal service-role changes (`trade-service` as Gateway, `order-matcher` as LMAX node) as
  implementation deltas first, external contract deltas second
