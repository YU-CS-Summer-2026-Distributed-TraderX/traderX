# Requirements

FR-TS01: Validate generated JWT reconciliation configuration and refuse missing/malformed wiring; no obsolete token-key requirement.
FR-TS02: Execute the local wrapper fresh-JAR path before Docker, and prove failures cannot reach image build.
FR-TS03: One bare matcher test invocation uses compatible synthetic authentication fixtures, with positive and negative authentication cases executed. Production authentication is unchanged.
FR-TS04: Use the module-declared toolchain/test roots, require attributable nonzero test reports, and preserve failed attempts before reruns. Report skips, dedicated-hardware cases and unmet live prerequisites explicitly.
FR-TS05: Continue the full script and composed-service suite sequentially in owned snapshots/checkouts; no cloud, private tape ingestion or retained-state deletion.
NFR-TS01: Preserve all existing canonical dirty/private files and staging. Separate candidate review from source integration approval.

FR-TS06: Baseline and composed legacy UI tests must bind real Angular inputs, supply each layer's injected collaborators, exercise account scopes/queue/upsert/cleanup behavior and retain existing coverage gates. Test ownership must survive state004's historical patch composition.
FR-TS07: Capture failed/skipped browser counts and refuse zero executed reports; preserve an earlier valid summary if a later write fails. Source identity includes new source and tracked fixtures, while disposable untracked build outputs cannot change it.
