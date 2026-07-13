# Generation Hook: YU09-ops-hardening

- Hook script: `pipeline/generate-state-YU09-ops-hardening.sh`
- Render script: `pipeline/render-state-YU09-ops-hardening.sh`
- Feature pack: `specs/YU09-ops-hardening`
- Parent state: `YU08-execution-algo-engine`
- Overlay model: generate parent (which renders onto `YU07-historical-tick-store` →
  `YU06-eod-price-production` → `YU05-post-trade-compliance` → `YU04-durable-control-feeds` →
  `YU03-in-memory-risk-gateway` → `YU02-lmax-kubernetes` → `014-fdc3-intent-interoperability`),
  then overlay this state's `generation/runtime-overrides/` onto the shared component tree — the
  same per-file overlay mechanism every prior state in this lineage uses.

## Hook Responsibilities

1. Delegate direct invocation via `pipeline/generate-state.sh YU09-ops-hardening`.
2. Generate parent `YU08-execution-algo-engine` from a clean target root.
3. Overlay five k8s manifests with `secretKeyRef`-based credentials in place of literal values:
   `database-deployment.yaml`, `order-matcher-deployment.yaml`, `trade-processor-deployment.yaml`,
   `account-service-deployment.yaml`, `position-service-deployment.yaml`.
4. Overlay `order-matcher`'s `Journaler.java` (rotation), new `JournalArchiver.java`,
   `LmaxEngine.java` (wiring), `application.properties` (new `journal.archive.*` keys), and
   `build.gradle` (AWS SDK v2 S3 dependency).
5. Materialize the state scaffold + spec-source copies under
   `generated/code/target-generated/YU09-ops-hardening`.
6. Inherit everything else (runtime harness, other manifests, GKE deploy scripts, observability
   stack, `tick-store`, `execution-algo-engine`) unchanged from `YU08-execution-algo-engine`.

The `pipeline/publish-generated-state-branch.sh` fix (gap #4, gradlew bootJar before docker build)
is **not** part of this overlay — it's a change to the pipeline script itself, so every state's
local `run-state-kind` bring-up (including states generated before YU09 exists) benefits from it
immediately, not just YU09.

## Shared-file override caution (see research.md)

Every manifest this state overrides (`database-deployment.yaml`,
`order-matcher-deployment.yaml`, `trade-processor-deployment.yaml`,
`account-service-deployment.yaml`, `position-service-deployment.yaml`) has exactly one prior
ancestor override each (YU06 for `database-deployment.yaml`/`account-service-deployment.yaml`,
YU02 for the other three) — no ancestor between that override and YU09 also touches the same
file, so there is no intermediate change to merge; YU09's copy is the latest ancestor's file with
only the credential env entries swapped to `secretKeyRef`, everything else byte-identical.

`order-matcher`'s `LmaxEngine.java` has one prior ancestor override (YU05, the latest before
YU09) — YU09's copy is YU05's file with the new `journalArchiveEnabled`/`journalArchiveBucket`/
`journalArchiveGcsHmacKeyId`/`journalArchiveGcsHmacSecret` `@Value` params, fields, and
constructor call added, every other line unchanged.

Verify empirically after generating: regenerate, then grep the generated
`order-matcher-deployment.yaml` for `mariadb-credentials` **and** the YU05 risk-gateway markers
(`RISK_ENABLED`) in the generated `application.properties`, confirming YU05's content survived
alongside YU09's additions.

## Build / verify

```bash
bash pipeline/generate-state.sh YU09-ops-hardening
cd generated/code/target-generated/order-matcher && ./gradlew test
```

Deploy uses the inherited `YU08`/`YU02` GKE scripts/CI. Out-of-band credentials required before
pods reach Ready: `mariadb-credentials`, `auth-secrets` (both mandatory);
`order-matcher-journal-gcs-hmac` (optional, only needed to exercise the GCS upload leg of journal
archival) — see `quickstart.md`.
