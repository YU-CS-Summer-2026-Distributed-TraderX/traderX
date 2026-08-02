# 08 — YU-branded github.io site

> *"If you have time — customize the github.io website to be YU-branded. Dov wants to see this done so
> he can highlight it as an example of an organization running out on its own with TraderX. (He did this
> for an internal version of TraderX inside Morgan Stanley.) Sync up with Dov for any questions."*
> Lowest technical priority, but **disproportionate relationship value per hour** — this is the item a
> FINOS maintainer personally asked for and wants to showcase. Lane: docs/web. See [[00-INDEX]].

## Why it's worth doing despite being last

Everything else on this board makes the *system* credible. This one makes the *project* visible: Dov
wants to point at it as the reference example of an outside organization taking TraderX and running with
it. That's a FINOS-endorsed showcase slot for essentially a day of work — and it costs nothing on the
critical path because it's ideal filler when blocked on a spike, a rebase, or a cluster bring-up.

## The job

1. **Sync with Dov first.** He has done this before internally at Morgan Stanley, so ask what "branded"
   meant in that instance — what he changed, what he'd expect to see, and whether there's a template or
   prior example to follow. One message saves a day of guessing, and he explicitly offered.
2. **Brand the site as YU**: naming, styling, and landing content that presents this as an organization's
   own TraderX deployment rather than a fork of the demo.
3. **Make the content carry the story.** The site is the natural home for the material we've already
   proven — the state progression, the architecture, the performance and correctness results, and (once
   brief 01 reports) the upstream-rebase experience. Reuse; don't write new prose from scratch.
4. **Keep it honest.** Decks are achievement-focused, but a public site should stand up to scrutiny —
   quote the banked numbers and the load shape they were measured at, not rounded-up headlines.

## Numbers that are safe to publish (from the completed campaigns)

- Per-order throughput **190,300/sec at 4 gateways**, scaling ~linearly from 149,600 at 3.
- Consensus commit **185–227 µs**, stable across a 6× load sweep and every window depth tested.
- Matching/apply **0.45–0.57 µs**.
- Per-order **p50 under 1.5 ms sustained to 75k/s**, p99 ~2 ms at a correctly-sized window.
- HA: failover, snapshot/replay, and cold-follower rejoin from an empty disk, all proven live.
- Leader failover **under 200 ms** on a 3-member GKE cluster (post-fix fast mode, measured
  ~85–180 ms), plus **zero order-ID reuse** across every kill. Measure it with independent
  same-cadence probes — `/orders`, which needs a leader, against `/ready`, which is gateway-local —
  never a health-poll plus `kubectl delete pod`, which adds 400–600 ms of API and poll latency and
  once produced a fake 37 s tail.

**Do not publish**: single-run RTT absolutes to two significant figures (~1.5–2× run-to-run variance),
the retired "12k ceiling", the extrapolated ~440k consensus ceiling stated as measured, or the
**superseded ~2.0 s system-facing failover** from 2026-07-18 — it predates both the consensus-timeout
tuning and the gateway reconnect fix, and understates the system by an order of magnitude.

## Conventions

Docs/web only — no cluster, no branch propagation. `git push` goes to yaakov (this one may eventually
*need* a push to publish — get explicit sign-off before pushing anything public).
