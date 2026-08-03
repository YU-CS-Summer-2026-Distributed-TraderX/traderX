# Non-Functional Delta: YU09-ops-hardening over YU08-execution-algo-engine

| Req | Status | Notes |
|---|---|---|
| NFR-OH01 no throughput regression with archival disabled | **Done** | `bench-compare` against the YU08 baseline, `journal.archive.enabled=false` (the shipped default). |
| NFR-OH02 GCS upload off the journaler thread | **Done** | `JournalArchiver` runs its own single-thread `ExecutorService`; `archiveAsync` only submits, never blocks the caller. |
| NFR-OH03 no Secret value committed | **Done** | All three Secrets created via `kubectl create secret` per `quickstart.md`; `.gitignore`/repo hygiene unaffected since nothing is written to a tracked path. |
| NFR-OH04 required vs. optional Secrets | **Done** | `mariadb-credentials`/`auth-secrets` have no `optional: true` — pods block on `CreateContainerConfigError` until created, same as any other required config. `order-matcher-journal-gcs-hmac` is `optional: true`. |
