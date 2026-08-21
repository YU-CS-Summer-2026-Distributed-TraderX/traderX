# The book-grid rebuild no longer starts from nothing

> The values below are a record, not a rig you can query. Rig data is rolled deliberately and will
> be rolled again; treat every number here as an example of the shape, and re-derive from the rig in
> front of you before acting on it.

`scripts/proofs/yu16-book-grid.sh` claims to prove that a wiped member rebuilds and reaches the same
book as its peers. It deletes the victim pod and waits for the StatefulSet to bring it back:

```
${K} delete pod "order-matcher-cluster-${VICTIM}" --wait=true
```

**It does not delete the claim.** The cluster StatefulSet declares a `volumeClaimTemplates` entry, so
the claim outlives the pod. The member comes back holding the disk it had a moment ago, reads its own
snapshot, and replays only the tail it missed.

The identity assertion still passes. That is the problem: it passes for a reason weaker than the one
the proof is named after. A from-nothing rebuild and a tail replay are different claims about the
system, and only one of them is being made.

`scripts/proofs/yu17-swap-netting.sh` already does it correctly — it deletes the PVC first and then
the pod, in that order, and a comment there records the PVC-vs-emptyDir change as the reason.

## Why it drifted rather than broke

The proof was written when the member was `emptyDir`-backed, where deleting the pod *was* deleting
the state. The backing changed under it. Nothing failed, because the assertion the proof makes is
still true — it simply stopped being evidence for the sentence above it. A message in the same script
asserted `emptyDir` unconditionally for the same reason, and that one is now fixed (it reads the
backing off the rig); this one needs a rig run to change, not a wording change.

## What "fixed" looks like

Delete the claim before the pod, the way `yu17-swap-netting.sh` does, then re-run the proof on the
cluster rig and confirm two things that a tail replay would not show:

1. the member rebuilds from an **empty** disk — the restore reads a snapshot or replays from the log
   head rather than resuming from its own recent state;
2. the identity assertion still holds afterwards.

If (2) fails once the disk is genuinely gone, that is a real finding about rebuild, not about the
proof — and it is the finding this proof was written to surface.

**Do not change the delete order without re-running it.** Deleting a claim that a member still needs
and finding the assertion passes anyway would be the same class of vacuous pass one level down. See
`.claude/skills/vacuous-pass-audit`.

## Provenance

Found while fixing `hints-that-name-the-wrong-tier` (resolved 2026-08-21) — the wording fix on the
same line surfaced the behavioural one underneath it. The backing was read off the cluster rig and
confirmed as a `volumeClaimTemplates` entry at the time; read it again rather than trusting that.
