# 03 — Unit tests for the plain-vanilla TraderX baseline

> Dov's specific suggestion: build a **believable unit-test suite for the plain-vanilla TraderX state
> that came before our first state** — the code we forked and built on top of. This is the foundation of
> the credibility story: if the base we inherited is untested, everything above it is suspect.
> Lane: implementation. **GATED ON BRIEF 01** — see below. See [[00-INDEX]].

## ⚠️ Gate: do not start before the rebase spike reports

Brief 01 rebases **this exact code** onto a newer upstream. Tests written first get rewritten after.
**Wait for 01's go/no-go.** If the delta is small → rebase, then write these once. If it's large → 01
will say so, and the call may be to write these anyway and accept rework, or to start with brief 04
(our own layers, which upstream cannot touch) instead.

## Scope

The **inherited services**, not our engine: account-service, reference-data, position-service,
trade-processor, people-service, web-front-end. These are where coverage is genuinely thin — our own
matching/cluster/gateway layers are already well covered (see 02).

"Believable" is the operative word. Aim for the tests a reviewer would *expect* to find: domain logic,
validation and boundary conditions, error/failure paths, persistence mapping, and the API contracts
each service exposes. Not coverage theatre — tests that would actually catch a regression.

## Guidance

- **Let brief 02's gap map drive the order** — highest-narrative-value components first.
- **Test behaviour at the seams**, especially anything another service depends on. Those contracts are
  what integration tests (brief 04) will lean on.
- **Prefer tests that fail loudly on the failure modes we've actually hit.** This project has a
  documented history of *silent* failure: rows dropped by a VARCHAR width, orders rejected by a risk
  gate while HTTP still returned 200, trades dropped by an FK, IDs colliding across epochs. A test that
  asserts "the drop is signalled" is worth more than one that asserts the happy path.
- Keep them in the ordinary gradle suite so they run in CI — a capability outside the suite rots.

## Deliverable

A green, CI-run unit suite for the baseline services, with a short note on what was deliberately not
covered and why. Report the before/after test counts per service — that delta is presentation material.

## Traps

- **Layering:** if a service is overridden in a `runtime-overrides` layer, write the test against the
  **highest carrying layer on each branch**, and propagate — verify two ways (spec md5 **and** a
  re-rendered, marker-grepped tree). A test added to a shadowed layer is inert at generation.
- Per-service `schema.sql` files are **H2/test-only**; the real deployed schema lives only in
  `database-init-configmap.yaml`. Don't write tests that assume the H2 schema matches production.
- Run gradle suites **one at a time** (see 02 for the known flakes).

## Conventions

Commit per service or per logical group; `git push` goes to yaakov. No GKE needed.
