# Console sessions plan

## Goal

One identity per login on the console and the Desk, and order entry that works under ENGINE-REVIEW S1
without handing a signed-out visitor the console's JWT.

## Workstreams

1. Per-login session ids, a Desk sign-out that works, and no-store on `/auth/*` (de5e5d53).
2. Order entry on the admin gate, signed for a signed-in operator (12a0a21b).

## Key decisions

The console's only JWT is its admin token, so it is attached only behind the admin gate (ADR-082).

## Exit Criteria

SC-CS-01 and SC-CS-02 pass under `node --test`.
