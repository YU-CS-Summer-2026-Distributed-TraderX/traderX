# Generation derivation plan

Owner: Codex RI20-GENERATION-DERIVATION. Status: implementation and local qualification complete; review/integration pending.

1. Verify accepted base9528d8ff, current build entrypoints and independent review36 stale-render control. Claim a fresh isolated checkout and keep canonical/user/peer source untouched.
2. Use independent private full generation because existence, mtime, leaf-only copying and caller-generated receipts cannot establish full-ancestry derivation. Snapshot current source bytes, supply independent local HEAD metadata and compare complete contexts before any build effect.
3. Couple ordinary builder execution to private validated contexts. Admit all selected services as a set. Preserve explicit ancestor selection and tag policy.
4. Reproduce STP pre transforms from fresh derived source. Validate prepare and each build role independently, preserving the existing RI18 provenance schema and checks.
5. Exercise actual entrypoints with fake external tools, positive/current/cache/dirty-source cases and stale/missing/deleted/injected/forged negatives. Preserve an original omission control with attributable compiler/image sentinels.
6. Run the real full-ancestry pipeline and independent reference comparison; record nonempty ancestry/source/render hashes, generated maintenance-doc parity, required gates and cleanup. Return a scoped commit/evidence via LOCAL board only.

No runtime/financial/protocol/auth/feed changes. No canonical generation, integration, push, deployment, paid compute, live rig or image-tag mutation. Private full generation adds latency/storage; persistent receipts are deliberately not trusted as a fast path. A narrower authenticated cache would be separate work.
