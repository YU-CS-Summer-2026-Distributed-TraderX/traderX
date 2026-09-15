# Runtime Topology: EOD Risk Bundles

## Entrypoints

Python CLI commands `build`, `validate` and `mock`; see quickstart.md. Generated start/stop/status environment wrappers retain the inherited YU17 cluster harness; running those wrappers is distinct from the local bundle workflow. The generated test wrapper exercises only the bundle component.

## Components

Inherited YU17 services supply exported positions/contracts. The added eod-risk-bundles component executes as a local process and stores a manifest and byte-preserved CSVs in a caller-selected private directory. The mock writes an identified result to a separate new directory.

## Networking

Core local commands make no network calls. Opt-in gcs_stage.py invokes gcloud for read-only metadata and generation-pinned object downloads. The separate fake_worker.py process binds only to 127.0.0.1; --http-worker opts the coordinator into that loopback test protocol. No NATS subscriber is added.

## Startup / Health Order

1. Supply both completed EOD export files.
2. Build and validate their bundle.
3. Invoke the mock against the completed bundle.
4. Read the result only after successful command completion.

## Degraded Behavior

| Condition | Behavior |
|---|---|
| Mixed cut or invalid row | Build fails before publication |
| File or manifest corruption | Validation and consumption fail |
| Missing market data | Explicit NOT_SUPPLIED; no pricing claim |
| Existing output or active publication lock | Command fails; existing output stays intact |
| Hard-killed publisher | Staging/lock can remain; operator inspects before cleanup |
| Unsupported pricing capability | Every mock row is NOT_PRICED with MOCK_ONLY |

Local coordinator commands use a private filesystem inbox and a single-host SQLite store. An OS lock spans each command and worker execution; another command refuses while the owner is active. Default coordination has no new ports; the explicit fake worker uses a caller-selected or ephemeral loopback port. There are no new messaging subjects. Process-restart recovery occurs on run; real pricing is not implemented; GCS input staging is a separate explicit command.

The risk-extract producer optionally writes local completion receipts via RISK_EXTRACT_READY_DIRECTORY. The bridge reads those receipts and allowed local artifacts into the coordinator inbox. This adds no ports or messaging subjects. The in-process demo invokes sequenced service ingress and production renderers without deploying the EOD service chain.
