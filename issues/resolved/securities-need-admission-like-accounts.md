# The engine admits accounts and securities separately, and bring-up only ever did accounts

**Found 2026-08-20** from a live session: 12 instruments, 4 accounts, **16 orders, 0 accepted**.

## The gate

`BlpRiskState:204` (and again at 265, the second entry point):

```java
if (securityId < 0 || securityId >= securityEnabled.length || securityEnabled[securityId] == 0) {
    return remember(clientOrderKey, 0, RiskReason.UNKNOWN_SECURITY);
}
```

`securityEnabled[]` is written by `bootstrap()` from a snapshot, by `bootstrapSecurity()`, and by
`putSecurity()` — **which has no caller in the tree.** In practice the only live writer is the
sequenced `TYPE_SECURITY_CONTROL` event behind `POST /risk/control/security`.

## Why it looked like a broken instrument rather than an unadmitted one

`resolveSecurityId` **auto-registers a ticker on first sight** — it offers a SYMBOL_REGISTER and gets
back an id. So an unknown ticker resolves cleanly, occupies a symbol-table slot, and *then* fails the
risk gate. Nothing upstream reports a problem, the symbol table fills with names that cannot trade,
and the rejection names the security rather than the admission.

IBM worked, which is what made it read as a data problem: the fixtures happen to enable exactly one.

## Same shape as the account bug, one noun over

The account directory (`account-service`) and the engine's admitted set are two different things, and
a fresh epoch resets only the second — that was found on the same day and fixed as bring-up step 3b.
The security case is identical and hides better, because an unadmitted **account** at least fails
identically for every instrument, while an unadmitted **security** leaves one working name behind and
looks like a catalog problem.

## Fix

`scripts/yu15/bring-up-gke.sh` step 3c admits every catalog instrument (`4c9dda72`).

- **Admission alone is sufficient** — verified, no price seed required. `validationPrice` is the
  order's own price, and the staleness check is skipped while `lastPriceTime == 0`. Seeding would
  also set the mark, and under ADR-051 that applies only while no trade has printed, so it is an
  inert write on a used book and a silent mark change on a fresh one.
- The catalog is read from the **live** `price-publisher`'s `PRICE_TICKERS`. That list is already
  duplicated across `eod-chain.yaml`, `price-publisher` and `reference-data` manifests; a fourth copy
  in the script would drift from all three.
- Guarded on **shape** (`>= 20`), not emptiness: a partial read that admits a few and reports success
  is worse than no read at all.

Verified: 44/44 admitted, then real orders accepted across every class that had failed — SPY, AMZN,
QQQ, GLD, AAPL, UST-, UST-STRIP-, CORP- — with a deliberately bad bond lot still refused.

## Still open

**`putSecurity()` has no caller.** Either it is dead and should go, or something was meant to call it
and does not. Worth one look before it is trusted as a live path by someone reading the class.

**The console's band panel was right about a wrong rig.** It rendered *"never accepted — no accepted
order to compare against, so nothing can be concluded about the band"* for all eleven non-IBM names
and refused to infer a band from refusals alone. Recorded because it is the counter-example to the
usual finding: the display was correct and the environment was the defect.

Related: [[control-snapshot-carries-no-accounts]]

---

## Resolved 2026-08-21

Fixed as bring-up step 3c, then CORRECTED the same day: the first cut admitted `PRICE_TICKERS`
(44 names) while the console's picker loads `/reference-data/instruments` (533), so 489 instruments
the UI offers stayed UNKNOWN_SECURITY after the fix was declared done. Step 3c now reads the catalog
the UI offers. Verified live: 533/533 admitted, formerly-refused equities accepted, an off-catalog
ticker still UNKNOWN_SECURITY — widened, not removed.

**Residual, filed nowhere else:** `putSecurity()` still has no caller in the tree. Either dead or
something was meant to call it.
