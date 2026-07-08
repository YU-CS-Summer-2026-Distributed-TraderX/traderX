# Onboarding: Pricing/Risk Engine Integration

You're working standalone on pricing + risk (VaR, currently NPV-focused) as a separate service —
this doc is everything you need to slot it into the existing platform without needing to follow
our internal conventions or dig through the whole repo.

## Branch / where you are

Base your work on **`YU03-in-memory-risk-gateway`**, locally only — no need to push to the cloud,
and there's no cloud CI/CD pipeline for this branch yet (none needed for what you're doing).
`YU03` already has the in-memory pre-trade risk gateway (order admission, limits, kill switch) —
your service is a separate thing that feeds *into* it, not a replacement for it.

## The integration point: `/risk/control/*`

This is the only interface you need. It's a REST API, authenticated via two headers
(`X-Risk-Control-Token`, `X-Risk-Operator`), and every call is durably recorded (journaled) on the
gateway side — you don't need to worry about durability on your end.

| Endpoint | Body | What it does |
|---|---|---|
| `GET /risk/control/snapshot` | — | Current gateway state (accounts, securities, policy, kill switch) |
| `POST /risk/control/policy` | `{policyVersion, killSwitch, maxPositionQuantity?, maxConcentrationNotionalTicks?}` | Push updated limits |
| `POST /risk/control/account` | `{accountId, enabled}` | Enable/disable trading for an account |
| `POST /risk/control/security` | `{ticker, enabled, halted}` | Halt/enable a specific security |
| `POST /risk/control/restriction` | `{ticker, restricted}` | Restrict a security (auto-cancels its resting orders) |

Full contract: `specs/YU03-in-memory-risk-gateway/contracts/contract-delta.md` §4. Data shapes:
`specs/YU03-in-memory-risk-gateway/data-model.md`.

**⚠ Known gap — read before building around it:** the limits above (`maxPositionQuantity`,
`maxConcentrationNotionalTicks`, plus `creditLimitTicks`/`maxOrderQuantity`/`maxOrderNotionalTicks`
which aren't yet exposed via this API at all) are **global process-wide scalars, not per-account**.
The only per-account lever today is the binary `enabled` flag. If your VaR/credit engine computes
**per-account** limits (the normal shape for this kind of system), there's no clean endpoint for
that yet. Two options — pick based on how urgent this is for you:
1. **Works today**: treat a per-account breach as a hard stop — `POST /risk/control/account` with
   `enabled=false`. Coarse, but immediate.
2. **Correct, needs a small contract change**: extending `/risk/control/policy` (or adding a new
   `/risk/control/account-limit` endpoint) to accept per-account overrides. This is a scoped change
   to YU03's contract — flag it and we'll coordinate rather than you changing it unilaterally.

## What you'll need to pull from the platform (inputs)

| Data | Where | Notes |
|---|---|---|
| **Positions per account** | `position-service` (MariaDB read model) | Good enough for local/demo use. MariaDB is an async projection, not authoritative — if you need exact consistency, reconcile against the BLP's own journal/snapshot instead. |
| **Prices** | price-publisher's `pricing.*` NATS subject (live feed) | No EOD price-production service exists yet — snapshot last price yourself for now. |
| **Trade/execution history** | `trade-processor` MariaDB `trades` table | Available on YU03 as-is. |
| **Account/reference data** | `account-service` | Account list, entitlements. |

None of this requires you to run our generation pipeline or match our spec-kit conventions —
these are just existing REST/NATS endpoints you can hit directly.

## Where your work fits in the roadmap

Your work is tracked as backlog item #2 in `issues/HANDOFF-idea-INDEX.md` (overnight VaR/ES batch
grid) — worth a skim for the broader architecture thinking (deck-inspired two-path risk design:
real-time gateway + overnight batch), even though you're free to build it your own way. Related
docs in `issues/` if useful (not required reading): `HANDOFF-idea-overnight-var-batch.md`,
`HANDOFF-idea-eod-price-production.md` (the EOD-gate dependency, not yet built).

## Contact

Yaakov owns the GKE cluster, deploy pipeline, and the risk gateway (YU03) itself — ping him for
anything on the `/risk/control` contract, or if you hit the per-account limit gap above and want
to scope a fix together.
