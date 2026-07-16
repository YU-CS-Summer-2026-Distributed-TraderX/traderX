# Handoff: FIX Protocol Order-Entry Gateway

> One of 8 idea-handoffs produced from the professor's slide deck
> (`Combined_Financial_Systems_Deck` — deck 03 "Market Connectivity"). Each idea is a service the
> deck describes that TraderX does not have. This doc is self-contained for a fresh chat.

## What this chat accomplished

- Compared the deck's Trading & Execution requirements against TraderX. Deck 03 slide 17: real
  venues speak **FIX** (standard) and binary protocols (OUCH, BOE) with session-level sequencing,
  automatic reconnection, and "no duplicate orders and no lost orders across reconnections."
- Confirmed TraderX's only order ingress is REST/JSON through trade-service — no FIX, no binary
  session protocol, no session sequencing/recovery semantics. `possible_improvements.md` §4
  already flags REST/JSON ingress overhead as a measured bottleneck; this idea addresses realism
  AND that perf item together.

## Branch / repo state

- Repo: `/Users/yaakov/Desktop/Summer 26/lmax/traderX`, on `YU04-durable-control-feeds`
  (HEAD `5701f38`). Production: `YU02-lmax-kubernetes-blp-ha`. No code changes made this session.

## Goal for next chat

Design and scaffold a new YUxx state adding a **FIX gateway service**: an alternate order-entry
front door that accepts FIX 4.4 NewOrderSingle / OrderCancelRequest sessions and forwards into
the existing risk-gateway → BLP path, returning ExecutionReports (fills, partials, cancels,
rejects) over the FIX session.

Core requirements (all straight from the deck):
1. FIX session management: logon, heartbeats, sequence numbers, resend requests, gap fill.
2. Exactly-once semantics across reconnect: no duplicate orders, no lost fills (map FIX seq
   recovery onto the BLP journal/event-store, which is already the source of truth).
3. ExecutionReport publication for every order state transition (the BLP already emits these
   events on the output disruptor / NATS — the gateway translates them to FIX).
4. A small FIX client load-generator so the bench-compare suite can drive orders over FIX and
   compare against REST ingress numbers.

## Key files

| Path | Why it matters |
|---|---|
| `trade-service/` | Current REST ingress — the FIX gateway is a sibling front door |
| `order-matcher/` (BLP) | Source of truth for order state; its journal underpins FIX recovery |
| `possible_improvements.md` §4–5 | Existing evidence that ingress transport overhead dominates |
| `scripts/bench/` | Bench harness to extend with a FIX driver (`bench-compare` skill exists) |
| `specs/YU03-in-memory-risk-gateway/` | Spec-pack shape to copy |

## Architecture / context the next chat needs

- Library: **QuickFIX/J** is the obvious Java choice (matches the Java/Spring service stack);
  it handles session layer (seq numbers, resend, heartbeats) so the work is mostly the
  application-layer mapping: FIX order messages ↔ internal order commands, BLP output events ↔
  ExecutionReports.
- Deck framing worth preserving in the spec: TraderX's order-matcher *is the venue*, so this
  gateway makes TraderX look like a real exchange to member firms — the same role NASDAQ's FIX/
  OUCH front ends play in front of their matching engine. A stretch goal is a binary OUCH-style
  ingress for the latency story (ties to `possible_improvements.md` §4's "thinner binary
  ingress"), but FIX-first is the realism win.
- The gateway is warm-path (translation + session state), not hot-path. Keep it out of the BLP
  process; it talks to the same ingress the trade-service uses (or a thinner internal one — that
  choice is a research.md question).
- YU-state conventions: spec pack under `specs/YUxx-<name>/`, same-named branch, parent lineage,
  **commit but never push**, staging CI/CD only with explicit user approval. Beware the
  generation-pipeline dead-override gotcha (`HANDOFF-durable-control-feeds.md`).

## Decisions already made (don't re-litigate)

- FIX 4.4 first, binary protocol as stretch — FIX is the recognizable industry standard and the
  deck names it as the default venue protocol.
- The gateway translates; it does not own order state. The BLP journal remains the single source
  of truth, and FIX resend/recovery must be derived from it (deck: exactly-once delivery, a lost
  fill = position discrepancy = regulatory violation).

## Open questions / known issues

- Session store durability: in-memory session state is lost on pod restart; QuickFIX/J file/JDBC
  stores vs deriving from the journal — needs a research.md decision.
- Does the FIX gateway bypass trade-service and hit the risk gateway directly (faster, more
  code) or reuse trade-service's internal submission path (slower, less code)? Measure both with
  the bench harness.
- Client identity: FIX CompIDs need mapping to TraderX accounts — small but touches reference
  data; the YU05 auth/entitlements work may want to own that mapping later.

## Suggested first steps for next chat

1. Read this doc + `possible_improvements.md` §4–5 + `specs/YU03-in-memory-risk-gateway/spec.md`.
2. Confirm state id/name with the user (e.g. `YUxx-fix-gateway`).
3. Spike QuickFIX/J locally: accept a session, translate one NewOrderSingle into the existing
   order-submission call, return an ExecutionReport from the NATS fill event.
4. Write the spec-pack; make the FIX bench driver an explicit task so bench-compare covers it.
