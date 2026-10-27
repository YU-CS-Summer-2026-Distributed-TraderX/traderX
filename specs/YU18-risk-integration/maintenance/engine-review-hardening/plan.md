# Engine-review hardening plan

## Goal

Close every ENGINE-REVIEW finding, S1–S6 and F1–F11, and the regressions the S items caused, without
changing the wire or snapshot formats.

## Workstreams

1. Boundary (S1–S6), gateway-only.
2. Callers that must now sign: console order entry (12a0a21b), trade-service legacy `/trades` (951ed3c9),
   proof scripts (c8eb9233, 912456fe, 9fc12f69).
3. S4 regressions: control-feed registration (bbe9b040) and operator security control (8ea15b88).
4. Deterministic core (F1–F7, F9, F10): fresh-epoch rolls.
5. Recovery and FIX state (F8, F11).

## Key decisions

[ADR-082](../../system/adr-082-authenticated-boundary.md) for S1–S6. Each F item's rollout class is in
the spec.

## Exit Criteria

SC-ERH-01 and SC-ERH-02 on an empty-root render.
