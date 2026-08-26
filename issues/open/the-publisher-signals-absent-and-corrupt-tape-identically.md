# The publisher signals "no tape" and "corrupt tape" identically, so consumers must match prose

**Found 2026-08-26** by the UI lane building the replay-clock widget, and only because it was told to
exercise **both** failure arms rather than the one it had planned.

## What the publisher emits

`price-publisher /health.taqReplay` reports three states with two shapes:

| state | `error` | `source` / `days` / `position` |
|---|---|---|
| tape loaded | `null` | populated |
| **extract absent** | `"no extract at /etc/taq-replay/extract.json.gz"` | **all missing** |
| **extract corrupt** | `"did not gunzip+parse: incorrect header check"` | **all missing** |

**The last two are structurally indistinguishable.** A consumer can tell "something is wrong" from
`error` being non-null, and can tell nothing else without reading the message as English.

## Why that is worse than it sounds

**The absent case is a deliberate, documented demo step.** ADR-068's durability rule is only real
because the tape can be switched off, and `scripts/yu17/demo-taq-replay.md` rehearses exactly that:
delete the Secret, the walk resumes with honest provenance. **So the one state a consumer most needs
to render calmly is signalled identically to a genuine fault.**

Measured consequence, both observed on a live rig:

1. Keying on `error` alone rendered **the rehearsed revert as a red TAPE ERROR** — the honest mode
   reported as a breakage, in front of whoever is watching.
2. Keying on *structure* instead ("a tape is configured if the publisher describes one") rendered **a
   genuinely corrupt extract as the ordinary synthetic fallback** — a fault that looks like a normal
   demo, which is the more dangerous direction.

The UI lane shipped both readings before landing on matching the absence phrase and treating every
other message as a fault. That works and it fails safe. **It is also string-matching prose**, and the
day someone rewords that message the check silently reclassifies a fault as a routine revert.

## The fix, which belongs on the producer

Emit a discriminator alongside the message — `errorKind: "absent" | "parse" | …` — so consumers
branch on a value the publisher controls deliberately rather than on a sentence it happens to emit.
Small change, and it removes the only reason a client has to read English.

Until then, every consumer must independently rediscover this, and the runbook now warns about it in
the revert step.

## Not urgent, and not nothing

Nothing is broken on the rig; the console handles all three states correctly today. This is a
producer/consumer contract weakness that **has already produced two wrong renderings in one
afternoon**, and it will produce a third the next time somebody writes a client — most likely against
the demo's own honest mode.
