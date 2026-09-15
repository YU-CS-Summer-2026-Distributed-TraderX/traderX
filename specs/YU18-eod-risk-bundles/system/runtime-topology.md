# Runtime Topology: EOD Risk Bundles

## Entrypoints

Python CLI commands `build`, `validate` and `mock`; see quickstart.md. Generated start/stop/status environment wrappers retain the inherited YU17 cluster harness; running those wrappers is distinct from the local bundle workflow. The generated test wrapper exercises only the bundle component.

## Components

Inherited YU17 services supply exported positions/contracts. The added eod-risk-bundles component executes as a local process and stores a manifest and byte-preserved CSVs in a caller-selected private directory. The mock writes an identified result to a separate new directory.

## Networking

The added component opens no network listeners and makes no network calls. It subscribes to no NATS subjects.

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

Local coordinator commands use a private filesystem inbox and a single-host SQLite store. An OS lock spans each command and worker execution; another command refuses while the owner is active. There are no new ports or messaging subjects. Process-restart recovery occurs on run; real pricing and cloud transports are not implemented.
