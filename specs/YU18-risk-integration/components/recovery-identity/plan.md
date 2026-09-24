# Recovery identity plan

Owner: Codex RI-06. Status: review needed.

1. Audit actual authoritative layers, epoch keys, snapshots, mounts and ledger; reproduce receipt relabeling (complete).
2. Publish matrix/interfaces on board before broader edits; scope implementation to receipt lineage.
3. Add durable SQLite directory binding before publish; preserve explicit existing bridge interface and read-only producers.
4. Exercise failure ordering, concurrent writers, retained process recovery, distinct epochs and historic result selection using disposable local fixtures.
5. Generate YU18 sequentially, run source/generated suites and spec gates, deliver commits and evidence. Core/SQL migration is follow-up review, not implicitly included.

The YU18 layer is the only owner of these Python paths. The renderer copies the entire component; no ancestor or renderer edits are needed.
