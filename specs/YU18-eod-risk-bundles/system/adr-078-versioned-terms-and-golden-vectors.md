# ADR-078: Preserve bundle v1 and introduce explicit instrument terms

Status: Accepted for local TraderX implementation; exchange agreement with Alex pending.

The original extracts omit reference conventions needed to reconstruct pricing objects. Editing
their schemas or the v1 hash encoding would break the existing transport agreement. Bundle v2
therefore adds a fourth, exactly hashed terms artifact without changing CSV bytes or v1 identity.
Security terms resolve once across account positions; booked contract terms resolve by epoch and
contract identity. Missing conventions are enumerated, never defaulted into a generic product.

The initial profile covers Treasury and swap records only. Synthetic note schedules follow the
existing exporter accrual model and clearly state their additional assumptions. Real reference
loading and a faithful SOFR builder are separate work. The local mock accepts v2; HTTP draft-1
refuses it because that protocol cannot carry the artifact. No financial support is inferred.

Fixed v1 preimages/hashes independently guard compatibility. Fresh commands through the sequenced
service and production exporters reproduce the committed synthetic bill/note/SOFR fixtures. The
SOFR refusal is a named acceptance target, not claimed evidence from Alex's engine.
