# GKE member runtime plan

## Goal

Members on GKE that do not stall on the boot-disk journal, fit 1,024 books, do not trip the 200 ms
heartbeat on a GC pause, stop cleanly, and ship their logs.

## Workstreams

1. Dedicated archive volume and its StorageClass, rendered with the profile (858319c3, 951ed3c9).
2. 12 GB heap and 50 ms pause goal (d9224e37, 6af15608).
3. SIGTERM exit (37e1e30e).
4. Promtail on the CompSci profile (c8c8ac31), with the admission population updated to include it.

## Key decisions

[ADR-081](../../system/adr-081-gke-member-runtime.md).

## Exit Criteria

SC-GMR-01..03 on an empty-root render.
