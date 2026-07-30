# Generation Hook: YU04-durable-control-feeds

- Hook script: `pipeline/generate-state-YU04-durable-control-feeds.sh`
- Render script: `pipeline/render-state-YU04-durable-control-feeds.sh`
- Feature pack: `specs/YU04-durable-control-feeds`
- Parent state: `YU03-in-memory-risk-gateway`
- Overlay model: generate parent (which itself generates `YU02-lmax-kubernetes`, which renders onto
  `014-fdc3-intent-interoperability`), then overlay this state's `generation/runtime-overrides/`
  onto the shared component tree — the same per-file overlay mechanism every ancestor state uses.

## Hook Responsibilities

1. Delegate direct invocation via `pipeline/generate-state.sh YU04-durable-control-feeds`.
2. Generate parent `YU03-in-memory-risk-gateway` from a clean target root.
3. Overlay:
   - `order-matcher`: `ControlFeedSubscriber`, rewritten `ReplicaBootstrap`, extended
     `GatewayReplicaStore`, new metrics, config, tests.
   - `account-service`: outbox insert wiring, `AccountOutboxPublisher`, new snapshot endpoint,
     `build.gradle` NATS client dependency, tests.
   - `reference-data`: new persistence layer replacing the CSV-only cache, outbox publisher, new
     `POST /stocks` + snapshot endpoints, `package.json` NATS client dependency, tests.
   - **Not** a generation-pipeline overlay: the new outbox/`stocks`/`*_source_epoch` tables. Confirmed
     empirically (marker-comment test) that `postgres-database-replacement` — the directory an
     ancestor state (`YU01-lmax-sequencer`) owns the MariaDB init SQL under — is pruned
     from the generated tree for every k8s-era state (010 onward); a YU04 override of that path is
     silently dropped. The schema addition instead lives directly in the new
     `cluster-addons/yu04-staging/database.yaml` this state's own CI/CD pipeline creates. See
     `research.md` for the full empirical trace.
4. Materialize the state scaffold + spec-source copies under
   `generated/code/target-generated/YU04-durable-control-feeds`.
5. Inherit everything else (runtime harness, manifests, GKE deploy scripts, observability stack)
   unchanged from `YU03-in-memory-risk-gateway`/`YU02-lmax-kubernetes`.

## Verification discipline (do not skip)

Per the recurring generation-pipeline gotcha found during the YU03→YU04 handoff: before trusting
that a `runtime-overrides/<path>` file actually reaches the generated/deployed output, add a
one-line marker comment, run `bash pipeline/generate-state.sh YU04-durable-control-feeds`, then
`grep` for the marker in `generated/code/target-generated/<same path>`. If it's missing, the file's
real source is a different, older mechanism (a legacy git-patch chain, a `cp -R`/aggregation step
inside a specific ancestor state's own render script, or — a *third* variant found while building
this state — a directory pruned outright by `install-generated-ci-assets.sh`'s per-state
`state_allowed_roots` allow-list). This was done for all three services: `order-matcher` (both an
edited existing file and a brand-new file), `account-service`, and `reference-data` (first state to
add any runtime-overrides for it) all propagate normally. The MariaDB init SQL path does not —
`postgres-database-replacement` is pruned for every k8s-era state — see `research.md` for the full
trace and the corrected plan (schema lands in `cluster-addons/yu04-staging/database.yaml` instead).

## Build / verify

```bash
bash pipeline/generate-state.sh YU04-durable-control-feeds
(cd generated/code/target-generated/order-matcher && ./gradlew test)
(cd generated/code/target-generated/account-service && ./gradlew test)
(cd generated/code/target-generated/reference-data && npm test)
```

Deploy uses a new, isolated staging pipeline (`cloudbuild-yu04-staging.yaml` /
`clouddeploy-yu04-staging.yaml` / `cluster-addons/yu04-staging/`) — never the production
`YU02-lmax-kubernetes` GKE deploy path, and never the `YU03-in-memory-risk-gateway` staging
namespace either.
