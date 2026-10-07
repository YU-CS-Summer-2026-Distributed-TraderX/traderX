# Book-memory accounting plan

Status: local implementation complete; controlled coordinator review/integration pending.
Owner: Codex RI24 book-memory lane. Base: a0d6da0bfbef481f301bb1172b6810cb8a4c9e3e.

1. Verify operative full-file source owners and current engine default. Read the RI24
   issue without repeating the historical OOM incident as a current rig observation.
2. Compile only the six dependency units needed by actual LimitBook plus the separate
   instrumentation agent in temporary storage. Record SHA-256 before/after the run.
3. Measure each book's arrays and instance through Instrumentation. Use VM array offsets
   and scales for payload/layout accounting; traverse shared order links by identity.
4. Preallocate an external small reservoir and attach modest orders directly. Compare
   book-owned objects with reachable/shared sums and list unmeasured categories explicitly.
5. Test source-derived inventory, levels/count/occupancy, reference width/alignment,
   refusal/cleanup and a rebuilt omitted-array observer. Exercise installed JDK21 and
   JDK25 separately; qualify results by each recorded VM profile.
6. Deliver raw JSON, executable commands, test logs and evidence hashes for review.
   Preserve RI24's separate OOM-exit milestone and open production sizing work.

Shared generation/architecture/contracts remain in the state parent. This standalone
tool consumes authoritative units directly and requires no generated source tree.
Production population/depth/history measurements and full-member retained-heap analysis
remain future RI13/RI24 work; this plan selects no limits or storage redesign.
