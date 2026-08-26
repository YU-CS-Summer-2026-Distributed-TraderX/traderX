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
other message as a fault. That works, and **it genuinely fails safe** — which this issue originally
got wrong and the UI lane corrected on 2026-08-26. The correction matters enough to record:

    if (t.source || t.days || t.position) return t.error ? 'error' : 'tape';
    if (!t.error) return 'synthetic';
    return /no extract at/i.test(t.error) ? 'synthetic' : 'error';

**Reword the absence message and the regex stops matching, so the state falls through to `error`** — a
routine revert renders as a red TAPE ERROR. That is a **false alarm**, noisy and immediately visible.
For a genuine fault to render as the honest mode instead, a *fault* message would have to begin
matching `no extract at`, which rewording the absence string cannot cause.

**So the drift direction is toward crying wolf, not toward going quiet**, and this issue is a
correctness-of-contract item rather than a latent silent failure. The first draft said the opposite,
and the lane's objection to that was the right one: *"silently hides a fault" is the kind of line that
gets copied into an ADR and then prioritised against the wrong risk.* **It is still string-matching
prose and should still be fixed** — just not urgently, and not for the reason first written.

## The fix, which belongs on the producer

Emit a discriminator alongside the message — `errorKind: "absent" | "parse" | …` — so consumers
branch on a value the publisher controls deliberately rather than on a sentence it happens to emit.
Small change, and it removes the only reason a client has to read English.

Until then, every consumer must independently rediscover this, and the runbook now warns about it in
the revert step.

## A second instance, and therefore a class (added 2026-08-26)

This is not a one-off. The same shape appeared twice in one afternoon, in the same producer:

| what a consumer needs | how it has to get it today |
|---|---|
| absent vs corrupt extract | **match the error message as prose** |
| the feed's flush cadence, to size a stillness threshold | **hardcode `FEED_FLUSH_MS`'s value from the Deployment** |

Both are facts the producer knows exactly and does not publish, so every consumer either guesses or
couples itself to something that was never a contract — a sentence, or an env var read out of band.
The stillness case already broke once for precisely this reason (`802f7ea0`): the console had no way
to read the cadence, sized a threshold against the wrong thing, and reported a healthy cluster as
faltering every flush cycle.

**The fix generalises: whatever a consumer must branch on, the producer should say on its health
surface.** Concretely, `errorKind: "absent" | "parse"` and the flush interval, both on
`price-publisher`/`feed-adapter` health. The UI lane has offered to delete its hardcoded constant the
day the interval is exposed.

## Not urgent, and not nothing

Nothing is broken on the rig; the console handles all three states correctly today. This is a
producer/consumer contract weakness that **has already produced two wrong renderings in one
afternoon**, and it will produce a third the next time somebody writes a client — most likely against
the demo's own honest mode.
