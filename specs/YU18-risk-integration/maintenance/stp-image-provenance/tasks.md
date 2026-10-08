# STP image provenance tasks

Status: local implementation complete; ready for review. Live acceptance is not asserted.

- [x] T-SIP01 / FR-SIP01: reproduce both original timestamp failures through the original proof with offline processes.
- [x] T-SIP02 / FR-SIP02-05: complete context/composition inventory, private snapshots, role transformation and artifact provenance.
- [x] T-SIP03 / FR-SIP06-07: wire actual builder and early proof admission; retain behavioral assertions and remove STP forced no-cache.
- [x] T-SIP04 / SC-SIP01-04: source/process positive and negative controls through actual entrypoints.
- [x] T-SIP05 / FR-SIP03-04: full isolated YU18 generation; validate exact YU18 transform, preserve legacy transform and check no-op rendering content.
- [x] T-SIP06 / SC-SIP05: complete disposable real Docker label/cache/payload roundtrip; synthetic jar qualification required.
- [x] T-SIP07: component and required spec gates; scoped yaakov commit and READY_FOR_REVIEW with preserved evidence.

Separate authorization, outside this delivery: production pair Java/Docker build, live STP/replace scenarios, retained-format recovery, deployment admission and node resolvability. Neither source tests nor the scratch-image control complete those activities.
