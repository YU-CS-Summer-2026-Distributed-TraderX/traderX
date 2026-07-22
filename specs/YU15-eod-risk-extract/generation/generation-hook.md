# Generation Hook: YU15-eod-risk-extract

- Hook script: `pipeline/generate-state-YU15-eod-risk-extract.sh`
- Render script: `pipeline/render-state-YU15-eod-risk-extract.sh`
- Feature pack: `specs/YU15-eod-risk-extract`
- Parent state: `YU14-listed-equity-options`
- Overlay model: generate YU14 and its complete YU13…YU02 ancestry, then apply YU15
  `generation/runtime-overrides/` last-wins to the shared component tree and copy this spec pack
  into the generated state artifact.

## Hook Responsibilities

1. Delegate direct invocation through `pipeline/generate-state.sh YU15-eod-risk-extract`.
2. Generate parent `YU14-listed-equity-options` from a clean target root.
3. Overlay YU15 order-matcher extract assets.
4. Render `reference-data/*.csv` into `order-matcher/src/main/resources/reference-data/`, so the
   producer reads the counterparty mapping from the image. The image copies `BOOT-INF/classes`
   wholesale to `/opt/app/classes`, so this needs no Dockerfile or manifest change and the spec
   pack stays the single source of truth.
5. Copy the complete state pack, including generated architecture and ADRs, into
   `generated/code/target-generated/YU15-eod-risk-extract/spec-source/`.
6. Inherit the YU14 runtime harness (start/stop/status/test wrappers) unchanged — YU15 adds a
   process alongside the cluster tier, not a new runtime surface for it.

## Overridden Files

Each file is a full-file override taken from the newest ancestor that owns it, with the YU15 delta
applied on top:

| File | Ancestor | YU15 delta |
|---|---|---|
| `sbe/blp-replication.xml` | YU14 | `RiskExtractMessage`, template 8 |
| `lmax/AeronReplicationCodec.java` | YU14 | encode/decode for template 8 |
| `cluster/MatchingEngineClusteredService.java` | YU14 | marker branch, cut render + hash, leader-side bridge, consensus-position accessor |
| `cluster/ClusterNodeMain.java` | YU13 | readiness and the applied metric report the consensus position; `blpSeq` stays visible as `engineApplied` |
| `kubernetes-runtime/manifests/base/database-init-configmap.yaml` | YU06 | instrument-identifier columns widened to `VARCHAR(32)` in both blocks, plus the `ALTER TABLE ... MODIFY COLUMN` migrations |

New files add no override risk: `RiskExtractCut`, `RiskExtractCsv`, `RiskExtractCutPublisher`,
`RiskExtractGcsSink`, `RiskExtractMain`, and `RiskExtractTest`.

## Kubernetes Assets

`generation/kubernetes/cluster/` carries the inherited cluster tier retagged to `yu15`, plus
`nats.yaml`, `eod-price-db.yaml`, `trade-processor.yaml`, and `risk-extract.yaml`. The database
runs the state's own DDL: `eod-price-db.yaml` mounts the `database-init-sql` ConfigMap from the
runtime-overrides layer, so there is exactly one copy of the schema and a proof taken on kind
tests what the state actually ships. Kustomize cannot reference a file outside its root, so that
ConfigMap is applied separately by the start script, alongside `gateway.yaml` — which the parent
also keeps outside the kustomization.

The NetworkPolicy gains the producer's pod label in its ingress allowlist; without it the
producer's Aeron client silently cannot reach any member.
