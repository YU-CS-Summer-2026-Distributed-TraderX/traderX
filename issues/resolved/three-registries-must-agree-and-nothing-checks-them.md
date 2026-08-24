# An account exists in three registries, and nothing checked they agree

**Found 2026-08-21** when a published EOD produced no extract. Third instance of one shape in two
days, so it is worth naming as a class rather than a third bug.

## The three registries

| registry | what it decides | who reads it |
|---|---|---|
| `account-service` directory | which accounts the UI offers | every account dropdown |
| the engine's `accountEnabled[]` | which accounts may trade | the risk gate, per order |
| `reference-data/counterparties.csv` | which accounts can be **reported** | the EOD risk extract |

Nothing reconciled them. Each gap fails at a different moment, and later in the pipeline each time:

1. **Directory vs engine** — an unadmitted account is offered by the UI and rejected
   `UNKNOWN_ACCOUNT` on every order. Fails at order entry. Fixed as bring-up step 3b.
2. **Catalog vs engine** — an unadmitted security resolves to an id and still cannot trade.
   Fails at order entry. Fixed as step 3c (and options, which are in neither catalog, as 3d).
3. **Directory vs counterparties.csv** — an unmapped account trades all day and breaks the
   END OF DAY. This one.

The later the failure, the more expensive it is, and this is the latest of the three.

## What happened

Directory: 8 accounts. `counterparties.csv`: 7. Account **17017** ("U.S. Treasury Trading Account")
was in the directory, was admitted to the engine by step 3b, traded normally in a 1,912-order demo —
and then the first cut that included it died:

```
RISK-EXTRACT-FAILED java.lang.IllegalStateException:
  risk extract: account 17017 has no counterparty mapping in reference data
```

Prices published (v8, 69 instruments, 0 flagged), PnL marked 48 rows, and **no artifact appeared**.
From the console that reads as the extract being broken; the cause was one missing row of reference
data, three services away.

**The extract is right to fail closed.** A regulatory artifact must not emit a position with no
counterparty and netting set, and must not invent one. The defect is that nothing noticed the gap
until the artifact was due.

## Fix

- `17017,CPTY-HARBOUR-TSY,NS-HARB-ISDA-01,USD` added to both `counterparties.csv` layers (YU14 and
  YU15 — patched together so no layer disagrees), and each file sorted by accountId so a reader can
  diff it against the directory by eye.
- Applied live as a ConfigMap over `/opt/app/classes/reference-data/counterparties.csv`, since the
  file is baked into the order-matcher image and a rebuild was not warranted for one row.
- **bring-up step 3f** now compares the directory against the mapping the risk-extract pod actually
  reads, and warns per unmapped account. It warns rather than fails: an unmapped account is a
  working trading rig with a broken end-of-day, which is worth knowing at bring-up rather than at
  the cut.

Recovery needed no re-publish: the JetStream durable redelivered the failed `eod.pnl.done` once the
mapping was readable, and v8 cut at consensus 3347 with all 48 rows and all 8 accounts.

## The rule

**When a thing must be registered in more than one place, the registration is not the invariant —
the agreement is, and it needs its own check.** Three registries, three separate outages, each found
by a user rather than by the system, each fixed by adding the reconciliation nobody had written.

Related: [[securities-need-admission-like-accounts]], [[an-epoch-roll-silently-drops-instrument-classes]]

---

## Resolved 2026-08-21

Account 17017 mapped in both `counterparties.csv` layers; bring-up step 3f now reconciles the
account directory against the mapping the risk-extract pod actually reads, warning per unmapped
account. Verified: the failed `eod.pnl.done` redelivered on its own and v8 cut at consensus 3347
with 48 rows and all 8 accounts.
