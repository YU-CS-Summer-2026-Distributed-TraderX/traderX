# The EOD risk extract: a consumer's guide

For the risk-engine side. You do not need to run TraderX, deploy anything, or understand the
matching engine to use this. You need two files out of a GCS bucket and the conventions below.

Everything here was verified against the real artifact
`gs://traderx-501015-risk-extracts/2026-07-23/v2/seq-396.csv` on 2026-08-06. Where a number appears
in this document it was recomputed from that file, not copied from a design doc.

---

## 1. What this is, in one paragraph

At end of day the trading cluster stops accepting business, freezes, and writes down every open
position as of one exact point in its own history. That point is a **consensus sequence number** —
position 396 on the totally-ordered log that all three cluster members replay identically. The
extract is not a database query. It is the state machine's own state at instant N, rendered to CSV,
and all three members independently render it byte-for-byte identically. That is the property that
makes it a defensible input to a risk calculation: there is exactly one answer to "what did we hold
at the close", and it does not depend on which machine you asked or when the query ran.

**Status as of 2026-08-06: this is a sample, not a live feed.** The GKE cluster that produces
extracts was torn down on 2026-08-01 to stop compute spend. The bucket is deliberately kept — it is
the deliverable — but nothing new is being written to it until the cluster is brought back up. Build
against the files that are there; they are real output from a real run, not fixtures.

---

## 2. Getting the files

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
# traderx-risk-extract schema=1
# consensusSequence=396
# sessionDate=2026-07-23
# priceSnapshotVersion=2
# cutSha256=8971cde45d5d8eb2b54e08ed004562751044a5d3a912d5c0d672279d00aac0ae
# rows=8
# cutConsistency=every row is the replicated state machine's state at consensusSequence ...
# netting=none; rows are un-netted at (accountId, security) grain
# quantityConvention=signed net position in contracts (options) or shares (equity)
# costBasisConvention=weighted average trade price per contract or share, excludes fees and
#   excludes the contract multiplier
# marketValueConvention=quantity * closingMark * contractMultiplier
# unrealizedPnlConvention=(closingMark - costBasis) * quantity * contractMultiplier
# markSourceLegend=EOD_SNAPSHOT=... ; CLUSTER_LAST_TRADE_AT_N=...
# optionIdentity=OCC symbol; underlying, expiry, call/put and strike are derivable from the security
accountId,security,instrumentType,quantity,contractMultiplier,costBasis,closingMark,markSource,...
```

Parse the `#` lines. `rows` and `cutSha256` in particular are how you detect a truncated download
without trusting the transport.

### Columns

| Column | Type | Notes |
|---|---|---|
| `accountId` | int | Opaque. Join key to `counterpartyId` / `nettingSetId` is already denormalised onto every row. |
| `security` | string | Ticker for equities; **OCC symbol** for options (`AAPL260918C00220000`). |
| `instrumentType` | `EQUITY` \| `OPTION` | Derived from the symbol shape, not stored separately. |
| `quantity` | signed int | **Negative means short.** Contracts for options, shares for equities. |
| `contractMultiplier` | int | `1` for equities, `100` for listed options. Already applied to `marketValue`/`unrealizedPnl`, **not** to `costBasis`. |
| `costBasis` | decimal(6dp) | Weighted average trade price **per contract or share**. Excludes fees. Excludes the multiplier. |
| `closingMark` | decimal(6dp) | The mark used. Per contract or share, same basis as `costBasis`. |
| `markSource` | enum | `EOD_SNAPSHOT` or `CLUSTER_LAST_TRADE_AT_N` — see below. |
| `markQuality` | enum | `OK`, `OVERRIDDEN`, `STALE`, or `LAST_TRADE` — see below. |
| `marketValue` | decimal(6dp) | `quantity * closingMark * contractMultiplier` |
| `unrealizedPnl` | decimal(6dp) | `(closingMark - costBasis) * quantity * contractMultiplier` |
| `currency` | string | `USD` throughout today. Do not hardcode it. |
| `counterpartyId` | string | e.g. `CPTY-ALPHA-CAP`. From reference data. |
| `nettingSetId` | string | e.g. `NS-ALPHA-ISDA-01`. From reference data. |

All decimals are exact to 6 places. They are computed in `BigDecimal` with rounding mode
`UNNECESSARY`, which means the producer **throws rather than round** — if a value ever needed
rounding you would get no file at all, not a quietly rounded one.

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

## 4. The five things most likely to bite you

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
last trade it aborts the whole extract. Which leads to:

**5. The extract fails closed, so a file that exists is complete.** There is no partial output. A
missing mark, an account with no counterparty mapping, a value that would need rounding — each
throws and nothing is written. You will never see a half-file, and you never need to
defensively skip malformed rows. If the file is there, all `rows` rows are there and they are all
well-formed. Assert on `rows` and move on.

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

## 7. If you ever want to generate your own

You do not need this yet, and it is a real amount of setup — but so you know the shape of it:

Everything is in the repo you already have. The extract runs on a 3-member Aeron cluster on
Kubernetes; on your own GCP project the path is `scripts/yu15/start-cluster-gke.sh`, and the
extract's own environment contract is documented in
`specs/YU15-eod-risk-extract/quickstart.md` (the `RISK_EXTRACT_*` variables — GCS bucket, HMAC
credentials, the NATS subjects). `scripts/proofs/yu15-risk-extract.sh` runs the whole thing
end to end and asserts the output is byte-identical across all three members and reproducible after
a member is killed and recovers.

There is also a local path that needs no cloud at all: a kind cluster on your laptop, via
`scripts/yu15/build-cluster-image.sh` then `scripts/yu15/start-cluster-kind.sh`. That is how the
sample above was validated. Ask when you get there and I will walk you through it rather than
leaving you with a script name.

**When it runs live**, delivery is announced on the NATS subject `risk.extract.ready`:

```json
{ "schema": 1, "uri": "gs://.../2026-07-23/v2/seq-396.csv", "consensusSequence": 396,
  "sessionDate": "2026-07-23", "priceSnapshotVersion": 2, "rows": 8,
  "sha256": "...", "cutSha256": "...", "quiesceWitnessSequence": 397 }
```

`quiesceWitnessSequence` is always `consensusSequence + 1` and is the cluster proving it was
genuinely quiet at the cut — nothing was sequenced between the cut and the witness. Until you have a
live cluster, polling the bucket is fine.

---

## 8. What we need from you

Design questions where your answer changes what we produce, roughly in order of how expensive they
are to change later:

1. **Do you want it netted, or raw?** Today it is raw at `(account, security)`. If your engine wants
   netting-set-level exposure we can emit that instead of, or alongside, the raw grain.
2. **Is `markQuality` actionable for you?** If an `OVERRIDDEN` or `STALE` mark should block a risk
   run rather than flow through, say so — we can fail the extract instead of labelling the row.
3. **Do you need anything that is not in the schema?** Trade-level detail, greeks, FX rates for
   non-USD, accrued interest. The engine has more state than it currently renders; adding a column
   is cheap, and adding one after you have built against the current shape is not.
4. **CSV, or something else?** Parquet or JSON-lines are both easy from here. CSV was chosen for
   inspectability, not because anything depends on it.
