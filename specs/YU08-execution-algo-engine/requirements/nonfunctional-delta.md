# Non-Functional Delta: YU08-execution-algo-engine over YU07-historical-tick-store

| Req | Status | Notes |
|---|---|---|
| NFR-AE01 independent of the BLP | **Done** | `execution-algo-engine` is a separate process/Deployment; no order-matcher Disruptor/journal/matching code is touched. |
| NFR-AE02 no new admission code path | **Done** | Children call the same `POST /orders` endpoint with the same request shape as the web front end's order ticket. |
| NFR-AE03 crash-resume with no operator step | **Done** | JetStream durable-consumer replay rebuilds every `ParentOrder` on boot; restart requires no manual intervention. |
| NFR-AE04 reuse existing client/framework | **Done** | `io.nats:jnats:2.20.5` (already a project dependency via order-matcher/account-service), Spring Boot (same shape as every other JVM service). |
