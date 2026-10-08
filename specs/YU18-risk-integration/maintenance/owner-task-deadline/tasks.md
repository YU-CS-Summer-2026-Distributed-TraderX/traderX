# Queued owner-task deadline tasks

Status: implemented and locally verified; coordinator review pending. Owner: Codex RI23.

- [x] Claim new clean accepted22d4c906 checkout; verify operative YU18 and12 onOwner sites.
- [x] Reproduce actual timeout/interruption late mutation: accepted node12 cases,5 intended failures,7 positive/control passes,0errors/0skips.
- [x] Add atomic WAITING/start/retire race guard; retain begun uncertainty and original wait/value/exception behavior.
- [x] Verify source/generated owner lifecycle and focused submission/ACK/encoding parity.
- [x] Run removal-only and naive-cancel negative controls; preserve exact restored source and reaped fixture threads.
- [x] Deliver scoped commit, raw XML, hashes, caller/signal boundaries and limitations for review.
- [ ] Coordinator review and controlled integration; indexes remain coordinator-owned.

This resolves no broader readiness/quorum, HTTP executor sizing, election outage, feed recovery or financial question.

Local evidence: source12/12 and generated48/48 (12 lifecycle,3 submission encoding,24 ACK metrics,9 inflight correlation),0failure/0error/0skip. Accepted baseline12 cases has5 intended lifecycle failures with7 positive/control passes. Removal-only control fails1 targeted case; naive-cancel control fails2 started cases in the12-case suite. Four repository gates and25 component packs pass. Actual consensus, elections, HA, retained recovery, deployment performance and financial acceptance are not established.

## Pipeline extension

- [x] Verify reviewed76071dc8 dependency and claim same isolated checkout/source.
- [x] Reproduce actual default-budget queued timeout and interruption: both later encode/offer, retain a permit and evade the unregistered reaper.
- [x] Reuse atomic unstarted claim; track acquisition explicitly; preserve started/ACK/reaper ownership and ambiguous outcomes.
- [x] Source/generated forced race, error and permit conservation checks; omission/double-release controls.
- [x] Deliver scoped follow-up with dependency and limits; coordinator review/integration remains pending.

Pipeline evidence:22 source and58 generated cases pass,0failure/0error/0skip;10 new pipeline cases plus12 owner,3 encoding,24 ACK and9 correlation. Each omission/duplicate-release control fails one runtime assertion. Four repository gates/25 component packs pass. Dependency76071dc8 remains separately reviewed; integration pending.
