# The EOD risk extract: a consumer's guide

For the risk-engine side. You do not need to run TraderX, deploy anything, or understand the
matching engine to use this. You need two files out of a GCS bucket and the conventions below.

Everything here was verified against the real artifact
`gs://traderx-501015-risk-extracts/2026-07-23/v2/seq-396.csv` on 2026-08-06. Where a number appears
in this document it was recomputed from that file, not copied from a design doc.

**One exception, stated up front.** That artifact is a **schema 2** file. The two schema-3 columns
(`lastCouponDate`, `accruedInterestFraction`, added 2026-08-12) are therefore not in it — they are
verified by unit test against hand-computed values, not yet against a delivered artifact. Produce
one locally (§2A) if you want to see them; everything else in this guide is unchanged and still
recomputed from the real file.

---

## 1. What this is, in one paragraph

At end of day the trading cluster stops accepting business, freezes, and writes down every open
position as of one exact point in its own history. That point is a **consensus sequence number** —
position 396 on the totally-ordered log that all three cluster members replay identically. The
extract is not a database query. It is the state machine's own state at instant N, rendered to CSV,
and all three members independently render it byte-for-byte identically. That is the property that
makes it a defensible input to a risk calculation: there is exactly one answer to "what did we hold
at the close", and it does not depend on which machine you asked or when the query ran.

---

## 2. Two independent ways to get one — and Google Cloud is not required

These are separate and neither depends on the other. Pick by what you are doing today.

| | **A. Produce your own** | **B. Read our sample** |
|---|---|---|
| Needs | Docker + kind, on your laptop | A Google Cloud login |
| Gives you | Live extracts from your own positions, on demand | Two files from our 2026-07-23 run |
| Cloud cost | None | None (reads only) |
| Good for | Building and testing your engine against changing data | Fixing the schema in your head in five minutes |

**A is the one that matters for you.** The cluster runs entirely on your machine and writes extracts
to a local path — `RISK_EXTRACT_SINK_URI=file:///data/risk-extracts` — with **no GCS, no HMAC
credentials, no GCP project, and no cost**. You can drive positions in, take a cut, and get a real
file. Nothing about YU15 requires the cloud.

The GCS bucket exists because *our* deployment happens to be on GKE and the extract is a delivered
artifact there. It is where you can read a known-good file without installing anything. That is its
whole role in your workflow.

**A file from either path is the same file.** Same schema, same header, same columns, same
conventions — verified by comparing a locally-produced extract against the GCS one. Everything in
sections 3 through 6 applies to both, so build your parser once.

> Not to be confused with the **TAQ corpus**, which lives in a different bucket and is the kdb
> tick-store data for a different piece of work. It has nothing to do with the risk extract.

---

## 2A. Produce your own, locally (recommended)

You need Docker and [kind](https://kind.sigs.k8s.io/), and the repo you already have. Nothing else.

```bash
cd traderX-YU15-eod-risk-extract           # the YU15 worktree

bash scripts/yu15/build-cluster-image.sh   # builds the cluster image (a few minutes, first time)
bash scripts/yu15/start-cluster-kind.sh    # 3-member Aeron cluster + services on kind
```

Give it fixtures so there are positions to extract:

```bash
kubectl --context kind-traderx-yu12-cluster -n traderx port-forward svc/order-matcher 18110:18110 &
MATCHER_URL=http://localhost:18110 bash scripts/yu15/seed-proof-fixtures.sh
MATCHER_URL=http://localhost:18110 bash scripts/proofs/seed-option-chain.sh   # adds listed options
```

Now trade whatever you want through `POST /orders` on `localhost:18110` — that is what ends up in
the extract:

```bash
curl -s -X POST http://localhost:18110/orders -H 'Content-Type: application/json' \
  -d '{"accountId":22214,"ticker":"IBM","side":"Buy","quantity":10,"limitPrice":187.00,
       "clientOrderId":"my-first-order"}'
```

### Or run a whole day, in ten minutes

Hand-placed orders and the fixtures give you a *shape*-complete extract with uninteresting content —
a handful of rows, every `costBasis` the seeded `200.000000`, accounts holding mirror images of each
other. If you want one that exercises your parser the way a real day would, run the flow generator:

```bash
bash scripts/sim/run-session.sh --minutes 10 --symbols 12
```

It runs a compressed session against the live book with distinct participants — a market maker
quoting two-sided around its own inventory, a momentum taker, a mean-reversion taker, and one
institutional parent order sliced by the execution-algo engine — with intensity varying across an
open hump, a quiet midday and a close hump. Ten minutes of it produced the 37-row, 12-security
extract this guide's examples come from: every security ends with both a net-long and a net-short
holder, and cost bases spread across the whole book.

One honest caveat, because it matters for what you conclude from the data. **This does not make the
prices real.** No market data is involved and no vendor feed is consulted; synthetic participants
produce a synthetic price. What it makes real is the price *formation*: the mark moves because
someone lifted the offer, because depth got consumed, because a large order pushed through levels.
Fills, marks and P&L all derive from one internally consistent market — which is what makes the
extract worth testing against — but the price levels themselves are ours, not the market's.

### Taking a cut

The extract's only trigger is the end-of-day chain finishing, so you close a session and the rest
runs itself:

```bash
K="kubectl --context kind-traderx-yu12-cluster -n traderx"

TOKEN=$($K exec deploy/trade-processor -- sh -c 'curl -fsS -X POST http://localhost:18091/auth/dev-token \
  -H "X-Auth-Master-Secret: kind-local-dev-token-secret-not-a-real-credential" \
  -H "Content-Type: application/json" \
  -d "{\"subject\":\"manual\",\"accounts\":[],\"admin\":true,\"ttlSeconds\":600}"')

$K exec deploy/trade-processor -- sh -c \
  "curl -fsS -X POST 'http://localhost:18091/eod/session/close' -H 'Authorization: Bearer $TOKEN'"

$K logs -f deploy/risk-extract
```

The log prints the announcement, which carries everything needed to fetch and verify the result:

```
RISK-EXTRACT-READY {"schema":3,"uri":"file:///data/risk-extracts/2026-08-06/v8/seq-43607.csv",
  "consensusSequence":43607,"quiesceWitnessSequence":43608,"rows":29,
  "cutSha256":"236992a8...","sha256":"..."}
```

### Getting the files onto your machine

```bash
K="kubectl --context kind-traderx-yu12-cluster -n traderx"
$K exec deploy/risk-extract -- find /data/risk-extracts -type f     # what exists

POD=$($K get pod -l app=risk-extract -o jsonpath='{.items[0].metadata.name}')
$K cp "${POD}:/data/risk-extracts/2026-08-06/v8/seq-43607.csv" ./seq-43607.csv
$K cp "${POD}:/data/risk-extracts/2026-08-06/v8/seq-43607.cut" ./seq-43607.cut
```

⚠️ **That directory is an `emptyDir`, so it is wiped when the pod restarts.** Copy anything you want
to keep. This is the one place the local path behaves differently from GCS, where objects are
permanent and write-once.

### Checking it works before you trust it

```bash
bash scripts/proofs/yu15-risk-extract.sh
```

That is the acceptance proof for this state. It takes a cut, asserts the file is **byte-identical
across all three cluster members**, kills a member, and asserts the recovered member replays and
re-renders the identical cut. If it passes, the extract on your rig carries the same guarantees as
ours. It takes a few minutes.

When you are done:

```bash
bash scripts/yu15/stop-cluster-kind.sh
```

---

## 2B. Read our sample from GCS (optional)

Only if you want a known-good file without setting anything up.

**This is a sample, not a live feed.** The GKE cluster that produced it was torn down on 2026-08-01
to stop compute spend. The bucket is deliberately kept — it is the deliverable — but nothing new is
being written to it. The files there are real output from a real run, not fixtures.

You have project access, so this should work without any grant. Check first:

```bash
gcloud auth login
gcloud config set project traderx-501015
gcloud storage ls gs://traderx-501015-risk-extracts/
```

If that last command errors with a 403, you need `roles/storage.objectViewer` on the bucket — ask
and it takes a minute. Everything else assumes it worked.

List what exists:

```bash
gcloud storage ls -r gs://traderx-501015-risk-extracts/
```

Today that returns:

```
gs://traderx-501015-risk-extracts/2026-07-23/v1/seq-394.csv
gs://traderx-501015-risk-extracts/2026-07-23/v1/seq-394.cut
gs://traderx-501015-risk-extracts/2026-07-23/v2/seq-396.csv
gs://traderx-501015-risk-extracts/2026-07-23/v2/seq-396.cut
gs://traderx-501015-risk-extracts/proof/<epochMillis>/seq-0.csv     <- ignore these
```

Pull the newest real one:

```bash
gcloud storage cp gs://traderx-501015-risk-extracts/2026-07-23/v2/seq-396.csv .
gcloud storage cp gs://traderx-501015-risk-extracts/2026-07-23/v2/seq-396.cut .
```

**Ignore `proof/`.** Those are written by an automated proof that checks the write-once path still
works; they are `seq-0` with no positions and exist only to prove the bucket rejects overwrites.

### The path tells you what you need to know

```
gs://traderx-501015-risk-extracts/ 2026-07-23 / v2 / seq-396.csv
                                   ─────┬────  ─┬─   ────┬────
                                   session date │        └─ consensus sequence N
                                                └─ price snapshot version
```

**`v2` is not a retry.** The price snapshot version comes from the EOD pricing pipeline's quality
gate. If a closing price is stale, missing, or looks like a spike, that version is flagged and
cannot be published; an operator resolves it, which mints a **new version**. So `v1` and `v2` for the
same date are two different sets of marks, both legitimate, and **v2 is the one that was accepted**.
Take the highest version for a date. `v1` is kept because the flagged version is immutable — that is
the audit trail, not junk.

Objects are **write-once**. The service account has `objectCreator` but not `objectAdmin`, so an
attempt to rewrite an existing path gets a 403 from GCS itself rather than silently replacing a
delivered file. A path, once you have read it, will never change underneath you.

---

## 3. The CSV

The file documents itself. Everything before the column header is a `#` comment carrying the
contract:

```
# traderx-risk-extract schema=3
# consensusSequence=396
# sessionDate=2026-07-23
# priceSnapshotVersion=2
# cutSha256=8971cde45d5d8eb2b54e08ed004562751044a5d3a912d5c0d672279d00aac0ae
# rows=8
# cutConsistency=every row is the replicated state machine's state at consensusSequence ...
# netting=none; rows are un-netted at (accountId, security) grain
# quantityConvention=signed net position in contracts (options), shares (equity) or USD face amount (treasuries)
# costBasisConvention=weighted average trade price per contract or share, excludes fees and
#   excludes the contract multiplier
# marketValueConvention=quantity * closingMark * contractMultiplier
# unrealizedPnlConvention=(closingMark - costBasis) * quantity * contractMultiplier
# markSourceLegend=EOD_SNAPSHOT=... ; CLUSTER_LAST_TRADE_AT_N=...
# optionIdentity=OCC symbol; underlying, expiry, call/put and strike are derivable from the security
# treasuryPriceConvention=costBasis and closingMark for instrumentType=TREASURY are clean prices as a
#   FRACTION of par (0.998780 = 99.878%), six decimals; the contract multiplier is 1
# treasuryStatic=coupon (annual %, fixed, semiannual) and maturityDate are joined from instrument
#   reference data; empty for non-treasury rows
# treasuryAccrualUnit=accruedInterestFraction is a FRACTION OF PAR in the same unit as closingMark,
#   so dirtyPrice = closingMark + accruedInterestFraction and settlementValue = quantity *
#   dirtyPrice; marketValue above stays CLEAN
# treasuryAccrualConvention=ACT/ACT (ICMA) semiannual: (days from lastCouponDate to sessionDate /
#   days in that coupon period) * coupon/2. Accrual runs to sessionDate ITSELF, not to a T+1
#   settlement date ...
# treasuryCouponSchedule=generated backwards from maturityDate in 6-month steps measured from
#   maturity, so nextCouponDate = lastCouponDate + 6 months; a short or long first coupon is NOT
#   modelled
# treasuryAccrualRounding=the only rounded value in this file (elapsed/period does not terminate);
#   HALF_EVEN at 6 decimals. Every other value is exact
accountId,security,instrumentType,quantity,contractMultiplier,costBasis,closingMark,markSource,...
```

Parse the `#` lines. `rows` and `cutSha256` in particular are how you detect a truncated download
without trusting the transport.

**Schema history.**

- **Schema 2** (YU16) appends `coupon` and `maturityDate` after `nettingSetId`, adds `TREASURY` to
  `instrumentType`, and states the bond price convention in the header.
- **Schema 3** (YU16, 2026-08-12) appends `lastCouponDate` and `accruedInterestFraction` after
  `maturityDate`, and states the accrual convention in the header.

Every earlier column keeps its name, position and meaning at every bump, so a reader that indexes
columns **by header name** reads any later file unchanged; a reader that hardcoded a column count
must widen. The `.cut` sidecar is unchanged across both bumps (`#cut schema=1`) — it is engine
state, and neither bump touched the engine. Both new columns are derived at render time from
static the extract already had.

### Columns

| Column | Type | Notes |
|---|---|---|
| `accountId` | int | Opaque. Join key to `counterpartyId` / `nettingSetId` is already denormalised onto every row. |
| `security` | string | Ticker for equities/ETFs; **OCC symbol** for options (`AAPL260918C00220000`); maturity-keyed instrument key for Treasuries (`UST-20280630`). |
| `instrumentType` | `EQUITY` \| `OPTION` \| `TREASURY` | Options by OCC symbol shape; **`TREASURY` by join against instrument reference data** (schema 2), never by prefix-parsing. ETFs report `EQUITY` — arithmetically they are. |
| `quantity` | signed int | **Negative means short.** Contracts for options, shares for equities/ETFs, **USD face amount for Treasuries**. |
| `contractMultiplier` | int | `1` for equities and Treasuries, `100` for listed options. Already applied to `marketValue`/`unrealizedPnl`, **not** to `costBasis`. |
| `costBasis` | decimal(6dp) | Weighted average trade price **per contract or share**; for a Treasury, the average clean price as a **fraction of par** (`0.998780` = 99.878%). Excludes fees. Excludes the multiplier. |
| `closingMark` | decimal(6dp) | The mark used. Per contract or share, same basis as `costBasis`. |
| `markSource` | enum | `EOD_SNAPSHOT` or `CLUSTER_LAST_TRADE_AT_N` — see below. |
| `markQuality` | enum | `OK`, `OVERRIDDEN`, `STALE`, or `LAST_TRADE` — see below. |
| `marketValue` | decimal(6dp) | `quantity * closingMark * contractMultiplier` |
| `unrealizedPnl` | decimal(6dp) | `(closingMark - costBasis) * quantity * contractMultiplier` |
| `currency` | string | `USD` throughout today. Do not hardcode it. |
| `counterpartyId` | string | e.g. `CPTY-ALPHA-CAP`. From reference data. |
| `nettingSetId` | string | e.g. `NS-ALPHA-ISDA-01`. From reference data. |
| `coupon` | decimal or empty | **Schema 2.** Annual coupon %, fixed, semiannual — Treasury rows only, joined from instrument reference data. Empty otherwise. |
| `maturityDate` | ISO date or empty | **Schema 2.** Treasury rows only, same join. Empty otherwise. |
| `lastCouponDate` | ISO date or empty | **Schema 3.** The coupon date `accruedInterestFraction` accrued from. Treasury rows only. `nextCouponDate` is this + 6 months. |
| `accruedInterestFraction` | decimal(6dp) or empty | **Schema 3.** Accrued interest as a **fraction of par**, same unit as `closingMark`. Treasury rows only. |

All decimals are exact to 6 places. They are computed in `BigDecimal` with rounding mode
`UNNECESSARY`, which means the producer **throws rather than round** — if a value ever needed
rounding you would get no file at all, not a quietly rounded one.

`accruedInterestFraction` is the one deliberate exception, and it has to be: `elapsed days / days
in period` does not terminate in decimal, so no amount of care makes it exact. It rounds
`HALF_EVEN` at 6 decimals, deterministically, so the byte-identical-across-members guarantee is
untouched. Everything else in the file is still exact-or-nothing.

### The two derived columns are redundant on purpose

`marketValue` and `unrealizedPnl` are computable from the other columns. They are shipped anyway so
you can assert your parser agrees with the producer before trusting anything else. On the real file:

```
10031, AAPL260918C00220000, qty 6, mult 100, costBasis 12.000000, closingMark 26.785000
  marketValue    = 6 * 26.785 * 100          = 16071.0   ✓ matches file
  unrealizedPnl  = (26.785 - 12.0) * 6 * 100 =  8871.0   ✓ matches file
```

If your recomputation disagrees, you have a convention wrong — most likely the multiplier, which is
applied to the value columns but not to `costBasis`.

---

## 4. The six things most likely to bite you

**1. Rows are un-netted, at `(accountId, security)` grain.** `netting=none` in the header is a
statement, not a placeholder. There is one row per account per security, and nothing has been
offset. In the sample, account `10031` is long 40 AAPL and `11413` is short 40 AAPL. Firm-wide those
net to zero; the extract will never tell you that. Netting is **your** engine's job, and the
`nettingSetId` column is there so you can do it — but be deliberate about it, because netting across
accounts that share a netting set is a very different calculation from netting within an account.

**2. `costBasis` excludes the multiplier, `marketValue` includes it.** This is the single easiest
mistake to make. An option at `costBasis 12.00` with `multiplier 100` cost 1200.00 per contract. If
you compute cost as `quantity * costBasis` you will be off by 100× on every option line.

**3. Options can be marked from the cluster's own last trade, not a published close.** Listed
options have no published closing price in this pipeline, so `markSource` falls back to
`CLUSTER_LAST_TRADE_AT_N` — the matching engine's last trade price *at exactly sequence N*, i.e. the
same instant as the position. It is internally consistent but it is **not a market close**. If your
engine distinguishes observable market marks from internal marks, this column is where you split
them. (In the sample, the option is marked `EOD_SNAPSHOT` because a close happened to exist for it —
do not assume that is always true.)

**4. `markQuality` is not always `OK`.** It carries the pricing pipeline's verdict through to you:

| Value | Means |
|---|---|
| `OK` | Published closing price, passed the quality gate clean. |
| `OVERRIDDEN` | The gate flagged it and an operator overrode it. The mark is a human decision. |
| `STALE` | Published, but the price is older than the freshness threshold. |
| `LAST_TRADE` | No published close; the cluster's own last trade at sequence N was used. |

A row is never emitted with *no* mark — if the producer can find neither a published price nor a
last trade it aborts the whole extract.

**These two columns are instrument-agnostic.** `markSource` and `markQuality` mean exactly the same
thing on a `TREASURY` row as on an `EQUITY` or `OPTION` row — same enum values, same semantics, same
fallback rule. A Treasury with a published close reports `EOD_SNAPSHOT`/`OK`; one without reports
`CLUSTER_LAST_TRADE_AT_N`/`LAST_TRADE`, meaning the mark is the cluster's own last trade at sequence
N. There is no bond-specific mark path and no bond-specific quality code. Whatever gate you apply to
`markQuality` today applies unchanged to bonds.

Which leads to:

**5. The extract fails closed, so a file that exists is complete.** There is no partial output. A
missing mark, an account with no counterparty mapping, a value that would need rounding — each
throws and nothing is written. You will never see a half-file, and you never need to
defensively skip malformed rows. If the file is there, all `rows` rows are there and they are all
well-formed. Assert on `rows` and move on.

**6. Bond prices and `marketValue` are CLEAN. Accrued is a separate column, and it is a fraction.**
This is the bond equivalent of mistake 2, and it is the same shape: two numbers that look like they
are in the same unit until you multiply one of them by the wrong thing.

```
UST-20280630, qty 100000 (USD face), closingMark 0.998780, accruedInterestFraction 0.002367
  dirtyPrice      = 0.998780 + 0.002367                = 1.001147
  marketValue     = 100000 * 0.998780                  =  99,878.00   <- the file's column, CLEAN
  settlementValue = 100000 * 1.001147                  = 100,114.70   <- yours to compute
  accrued cash    = 100000 * 0.002367                  =     236.70
```

Three things to hold on to:

- **Accrued is a fraction of par, not a cash amount and not a percentage.** Same unit as
  `closingMark` on purpose, so `clean + accrued` is the dirty price with no scaling step in between
  — that missing step is where a 100× goes.
- **`marketValue` and `unrealizedPnl` exclude accrued.** They are clean-price columns. If your
  engine wants dirty market value, add `quantity * accruedInterestFraction` yourself. We do not fold
  it in because a clean-price P&L and a dirty-price P&L are different numbers and you should pick
  deliberately rather than inherit ours.
- **Accrual runs to `sessionDate`, not to a settlement date.** Every other column in the file is
  as-of `sessionDate`; making one column as-of T+1 would make the row internally inconsistent, and
  we carry no holiday calendar to roll it with honestly. `lastCouponDate` is emitted precisely so
  you can roll it forward yourself under whatever calendar you use.

The schedule is generated backwards from `maturityDate` in six-month steps, so `nextCouponDate =
lastCouponDate + 6 months` and a short or long **first** coupon is not modelled. For bonds years
from issue that never bites; it is written down because it is an assumption, not a fact.

---

## 5. The `.cut` sidecar, and verifying integrity

Each CSV has a `.cut` beside it. That is the **raw pre-derivation state** — integer ticks, no
marks applied, no arithmetic done:

```
#cut schema=1 seq=396 sessionDateEpochDay=20657 priceVersion=2 rows=8
accountId,security,quantity,avgCostTicks,contractMultiplier,lastTradePxTicks
10031,AAPL,40,150500000,1,151000000
```

Ticks are **millionths**: `150500000 / 1e6 = 150.50`. The CSV's `costBasis 150.500000` is exactly
this value, converted.

Two reasons you care:

- **Integrity.** The CSV header's `cutSha256` is the SHA-256 of the `.cut` file. Verified:

  ```bash
  shasum -a 256 seq-396.cut
  # 8971cde45d5d8eb2b54e08ed004562751044a5d3a912d5c0d672279d00aac0ae
  grep '^# cutSha256=' seq-396.csv
  # 8971cde45d5d8eb2b54e08ed004562751044a5d3a912d5c0d672279d00aac0ae
  ```

  These match on the real artifact. Check it on ingest; it is two lines of code and it is what tells
  you the positions you are about to risk-manage are the positions the cluster actually held.

- **Full precision.** If you would rather do the multiplier and mark arithmetic yourself in integer
  space and avoid decimal-string parsing entirely, the `.cut` is the better input and the CSV
  becomes your cross-check.

---

## 6. Parsing it

```python
import csv, hashlib

def load_extract(csv_path, cut_path=None):
    meta, rows = {}, []
    with open(csv_path, newline="") as f:
        lines = f.read().splitlines()

    header_idx = next(i for i, l in enumerate(lines) if not l.startswith("#"))
    for l in lines[:header_idx]:
        body = l.lstrip("#").strip()
        if "=" in body:
            k, _, v = body.partition("=")
            meta[k.strip()] = v.strip()

    for r in csv.DictReader(lines[header_idx:]):
        r["quantity"] = int(r["quantity"])
        r["contractMultiplier"] = int(r["contractMultiplier"])
        for k in ("costBasis", "closingMark", "marketValue", "unrealizedPnl"):
            r[k] = float(r[k])           # use Decimal if you need exactness downstream
        # schema 3, treasury rows only — empty on every other instrument type
        r["accruedInterestFraction"] = float(r["accruedInterestFraction"] or 0.0)
        rows.append(r)

    # fail closed, the same way the producer does
    assert len(rows) == int(meta["rows"]), f'expected {meta["rows"]} rows, got {len(rows)}'
    if cut_path:
        digest = hashlib.sha256(open(cut_path, "rb").read()).hexdigest()
        assert digest == meta["cutSha256"], "cut digest mismatch — do not trust this extract"

    for r in rows:                        # verify you share the producer's conventions
        mv = r["quantity"] * r["closingMark"] * r["contractMultiplier"]
        assert abs(mv - r["marketValue"]) < 1e-6, f'convention mismatch on {r["security"]}'

    return meta, rows
```

That last loop is worth keeping permanently, not just during bring-up. It is the check that fails
the day someone changes a convention on our side without telling you.

---

## 7. Consuming it as a feed, and a note on the cloud

**The readiness announcement.** However the extract is produced, delivery is announced on the NATS
subject `risk.extract.ready`:

```json
{ "schema": 3, "uri": "file:///data/risk-extracts/2026-08-06/v8/seq-43607.csv",
  "consensusSequence": 43607, "sessionDate": "2026-08-06", "priceSnapshotVersion": 8,
  "rows": 29, "sha256": "...", "cutSha256": "236992a8...",
  "quiesceWitnessSequence": 43608 }
```

`quiesceWitnessSequence` is always `consensusSequence + 1`, and it is the cluster proving it was
genuinely quiet at the cut — nothing was sequenced between the cut and the witness. If you want your
engine to react to extracts rather than poll for them, subscribe to that subject; NATS is running in
the local cluster already. Polling the directory is perfectly fine to start with.

**On running it on GKE.** You do not need to, and I would not until you have a reason. The local
cluster produces the identical artifact with the identical guarantees, at no cost and with no cloud
account involved. If you later want it on your own GCP project, `scripts/yu15/start-cluster-gke.sh`
exists but is **not** a from-scratch bring-up — it applies manifests to a cluster that already
exists, and the manifests carry our project's image registry paths and a pre-created HMAC secret.
The work in front of that (project, APIs, Artifact Registry, four `linux/amd64` image builds, a GKE
cluster with two node pools, a bucket with `objectCreator`-but-not-`objectAdmin` IAM, a service
account and its HMAC pair) is not written down anywhere yet. Ask when you actually want it and it
will get written properly rather than guessed at.

---

## 8. What we need from you

Design questions where your answer changes what we produce, roughly in order of how expensive they
are to change later:

1. **Do you want it netted, or raw?** Today it is raw at `(account, security)`. If your engine wants
   netting-set-level exposure we can emit that instead of, or alongside, the raw grain.
2. **Is `markQuality` actionable for you?** If an `OVERRIDDEN` or `STALE` mark should block a risk
   run rather than flow through, say so — we can fail the extract instead of labelling the row.
3. **Do you need anything that is not in the schema?** Trade-level detail, greeks, FX rates for
   non-USD. The engine has more state than it currently renders; adding a column is cheap, and
   adding one after you have built against the current shape is not. (Accrued interest **was** on
   this list — it shipped as schema 3, which is what the two new columns in §3 are.)
4. **Is accrual-to-`sessionDate` the right call for you?** We deliberately do not roll to a T+1
   settlement date (§4.6). If your engine wants settlement-date accrual as the delivered number
   rather than something you compute from `lastCouponDate`, that is a convention change we can make
   — but it needs a holiday calendar, which neither system has today.
5. **CSV, or something else?** Parquet or JSON-lines are both easy from here. CSV was chosen for
   inspectability, not because anything depends on it.
