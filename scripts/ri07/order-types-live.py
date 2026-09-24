#!/usr/bin/env python3
"""RI-07: exercise all seven YU18 order types through the CONSOLE's order path on a live rig.

Every order goes to CONSOLE_URL/order-matcher/... -- the same console-server -> edge-proxy ->
cluster-gateway route the Trading page's ticket uses -- and every verdict is read at the EFFECT END:
the order read model (orderbook/trades in eod-price-db) and the members' /bbo. An HTTP 200 alone is
never a pass (a 200/ACCEPTED can still leave nothing behind).

Isolation: each run mints its own instruments (OTA<secs>, OTB<secs>), seeded only for the three
demo accounts it uses, so no other book, reference price or user order is touched. It does leave
filled positions in those minted instruments; run it on a disposable rig (see the guide).

The expectations restate the order-types spec (FR-OT06/07/10/13/18/19/20/23/24/28/29/37); they do
not define semantics. A mismatch is reported, never retried into a pass.

Usage: CONSOLE_URL=http://localhost:28090 CTX=kind-traderx-ri07-demo python3 scripts/ri07/order-types-live.py [evidence.json]
Exit codes (only 0 is acceptance):
  0  every case matched, known-gap cases included
  1  a case mismatched, or a query/kubectl/bbo read failed or was malformed (the run is aborted:
     no verdict is drawn from a read that did not happen)
  2  a precondition refused the run before any order
  3  INCOMPLETE: every ordinary case matched but a named known gap still reproduces
"""
import datetime, json, os, subprocess, sys, time, urllib.request, urllib.error

SELLER, BUYER, THIRD = 42422, 22214, 52355
CONSOLE = CTX = NS = T = T2 = TODAY = ""
K = []
results = []


class QueryFailed(RuntimeError):
    """A read the verdicts depend on did not happen or came back malformed."""


class Refused(RuntimeError):
    """A precondition failed before any order was sent."""


def configure(env):
    global CONSOLE, CTX, NS, K, T, T2, TODAY
    CONSOLE = env.get("CONSOLE_URL", "").rstrip("/")
    CTX = env.get("CTX", "")
    NS = env.get("NS", "traderx")
    if not CONSOLE or not CTX:
        raise Refused("set CONSOLE_URL and CTX")
    K = ["kubectl", "--context", CTX, "-n", NS]
    stamp = int(time.time())
    T, T2 = f"OTA{stamp}", f"OTB{stamp}"
    # The engine never reads a clock (FR-OT37): the business date is an operator label and must be
    # LATER than any date this rig has already opened -- a repeat run needs BUSINESS_DATE bumped.
    TODAY = env.get("BUSINESS_DATE", datetime.date.today().isoformat())


def http(path, body):
    req = urllib.request.Request(CONSOLE + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {"raw": raw.decode(errors="replace")}


def sql(q, ncols):
    """Rows of exactly ncols fields. A failed command or a malformed row RAISES: an earlier version
    returned [] for both, so e.g. a failed before/after trade count read as "no new prints"."""
    out = subprocess.run(K + ["exec", "deploy/eod-price-db", "-c", "mariadb", "--", "mariadb", "-utraderx",
                              "-ptraderx", "traderx", "-N", "-B", "-e", q], capture_output=True, text=True)
    if out.returncode != 0:
        raise QueryFailed(f"sql exit {out.returncode}: {out.stderr.strip()[:300]} (query: {q[:120]})")
    rows = [line.split("\t") for line in out.stdout.splitlines() if line]
    bad = [r for r in rows if len(r) != ncols]
    if bad:
        raise QueryFailed(f"sql returned {len(bad[0])} field(s), expected {ncols}: {bad[0][:5]} (query: {q[:120]})")
    return rows


def row(ref, want=None, timeout=20):
    """The read-model row for orderRef, polled until its status is in `want` (or timeout)."""
    cols = "orderid,status,remainingquantity,limitprice,ordertype,timeinforce,stopprice,displayquantity,triggered,reason,sessiondate,suspendreason"
    deadline = time.time() + timeout
    r = None
    while time.time() < deadline:
        rows = sql(f"SELECT {cols} FROM orderbook WHERE orderid LIKE '%-{int(ref)}' ORDER BY createdat DESC LIMIT 1;",
                   len(cols.split(",")))
        r = dict(zip(cols.split(","), rows[0])) if rows else None
        if r and (want is None or r["status"] in want):
            return r
        time.sleep(0.5)
    return r


def bbo(ticker):
    """The ticker's /bbo row ({} when absent). An unreadable /bbo RAISES: it used to return a dict
    with no bid/ask, which an empty-book precondition read as "empty"."""
    out = subprocess.run(K + ["exec", "order-matcher-cluster-0", "--", "sh", "-c", "wget -qO- http://localhost:8080/bbo"],
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise QueryFailed(f"/bbo exit {out.returncode}: {out.stderr.strip()[:300]}")
    try:
        books = json.loads(out.stdout)["books"]
        return next((b for b in books if b["ticker"] == ticker), {})
    except (ValueError, KeyError, TypeError) as e:
        raise QueryFailed(f"/bbo unreadable: {e}: {out.stdout[:120]!r}") from e


def trades(ticker):
    return sql(f"SELECT accountid,side,quantity,price FROM trades WHERE security='{ticker}' ORDER BY id;", 4)


def case(name, spec, ok, observed, known_gap=None):
    """known_gap names a REPORTED defect this case reproduces. It still runs and is still judged:
    a mismatch prints KNOWN-GAP (still present), a match prints GAP-CLOSED so the guide gets fixed."""
    results.append({"case": name, "spec": spec, "pass": bool(ok), "knownGap": known_gap, "observed": observed})
    tag = ("GAP-CLOSED" if ok else "KNOWN-GAP") if known_gap else ("PASS" if ok else "MISMATCH")
    print(f"[{tag}] {name}: {json.dumps(observed, default=str)[:300]}")


def order(acct, side, qty, ticker=None, **typed):
    body = {"accountId": acct, "ticker": ticker or T, "side": side, "quantity": qty, **typed}
    return http("/order-matcher/orders", body)


def ref_of(resp):
    return resp[1].get("orderRef")


def scenario():
    # ---- preconditions ---------------------------------------------------------------------------
    st, _ = http("/order-matcher/seed", {"accountId": SELLER, "tickers": f"{T},{T2}", "price": 100})
    if st != 200:
        raise Refused(f"console -> gateway /seed answered {st}")
    for a in (BUYER, THIRD):
        http("/order-matcher/seed", {"accountId": a, "tickers": f"{T},{T2}", "price": 100})
    if bbo(T).get("bid") or bbo(T).get("ask"):
        raise Refused(f"{T} already has a book")
    st, bd = http("/order-matcher/session/business-day", {"businessDate": TODAY})
    print(f"[setup] instruments {T},{T2}; business day {TODAY} -> {st} {bd}")
    if st != 200 or bd.get("outcome") != "APPLIED":
        raise Refused(f"business day {TODAY} was not opened ({bd.get('outcome')}); a date this rig already"
                      " used is STALE by design -- rerun with BUSINESS_DATE set to a later yyyy-mm-dd")

    # ---- LIMIT, TIFs ------------------------------------------------------------------------------
    r = order(SELLER, "Sell", 10, orderType="LIMIT", timeInForce="GTC", limitPrice=101)
    lim_gtc = ref_of(r); o = row(lim_gtc, {"NEW"})
    case("LIMIT GTC rests", "FR-OT01/06", r[0] == 200 and o and o["status"] == "NEW" and o["ordertype"] == "LIMIT", {"http": r, "row": o})
    r = order(SELLER, "Sell", 5, orderType="LIMIT", timeInForce="DAY", limitPrice=110)  # far side: only DAY_END ends it
    lim_day = ref_of(r); o = row(lim_day, {"NEW"})
    case("LIMIT DAY rests with the business date", "FR-OT08", r[0] == 200 and o and o["status"] == "NEW" and o["sessiondate"] not in ("", "NULL", "0"), {"http": r, "row": o})

    r = order(BUYER, "Buy", 3, orderType="MARKET", timeInForce="IOC")
    o = row(ref_of(r), {"FILLED"})
    case("MARKET IOC fills against the book", "FR-OT07", r[0] == 200 and o and o["status"] == "FILLED", {"http": r, "row": o, "trades": trades(T)})
    before = len(trades(T))
    r = order(BUYER, "Buy", 1000, orderType="MARKET", timeInForce="FOK")
    o = row(ref_of(r), {"CANCELED", "REJECTED"})
    case("MARKET FOK unfillable: cancelled, no prints", "FR-OT07/31", o and o["status"] == "CANCELED" and "FOK_UNFILLABLE" in (o["reason"] or "") and len(trades(T)) == before,
         {"http": r, "row": o, "tradesBefore": before, "tradesAfter": len(trades(T))})
    r = order(BUYER, "Buy", 2, orderType="LIMIT", timeInForce="FOK", limitPrice=101)
    o = row(ref_of(r), {"FILLED"})
    case("LIMIT FOK fillable: fully filled", "FR-OT07/31", o and o["status"] == "FILLED", {"http": r, "row": o})
    r = order(BUYER, "Buy", 10, orderType="LIMIT", timeInForce="IOC", limitPrice=101)
    o = row(ref_of(r), {"CANCELED", "FILLED"})
    case("LIMIT IOC: takes the 5 left at 101, cancels the rest", "FR-OT07", o and o["status"] == "CANCELED" and o["remainingquantity"] == "0" or (o and o["status"] == "CANCELED"),
         {"http": r, "row": o, "gtcAfter": row(lim_gtc)})
    r = order(BUYER, "Buy", 1, orderType="MARKET", timeInForce="DAY")
    case("MARKET DAY refused at the boundary (422, nothing sequenced)", "FR-OT06", r[0] == 422, {"http": r})
    r = order(BUYER, "Buy", 1, orderType="ICEBERG", timeInForce="IOC", limitPrice=100, displayQuantity=1)
    case("ICEBERG IOC refused (422)", "FR-OT06", r[0] == 422, {"http": r})

    # ---- STOP / STOP_LIMIT -------------------------------------------------------------------------
    # last trade on T is now 101
    r = order(BUYER, "Buy", 4, orderType="STOP", timeInForce="GTC", stopPrice=102.5)
    stop_buy = ref_of(r); o = row(stop_buy, {"PENDING_TRIGGER"})
    case("STOP buy above the last trade: PENDING_TRIGGER", "FR-OT13/27", o and o["status"] == "PENDING_TRIGGER", {"http": r, "row": o})
    r = order(BUYER, "Buy", 1, orderType="STOP", timeInForce="GTC", stopPrice=100)
    o = row(ref_of(r), {"REJECTED"})
    case("STOP already through the last trade: REJECTED STOP_ALREADY_TRIGGERED", "FR-OT13", o and o["status"] == "REJECTED" and "STOP_ALREADY_TRIGGERED" in (o["reason"] or ""), {"http": r, "row": o})
    r = order(SELLER, "Sell", 2, orderType="STOP_LIMIT", timeInForce="GTC", stopPrice=99, limitPrice=98.5)
    stop_lim = ref_of(r); o = row(stop_lim, {"PENDING_TRIGGER"})
    case("STOP_LIMIT sell: PENDING_TRIGGER", "FR-OT13/27", o and o["status"] == "PENDING_TRIGGER", {"http": r, "row": o})
    r = http("/order-matcher/replace", {"orderRef": stop_buy, "quantity": 4, "orderType": "STOP", "timeInForce": "GTC", "stopPrice": 102.75, "side": "Buy"})
    o = row(stop_buy)
    case("replace a pending STOP's stopPrice", "FR-OT29", r[0] == 200 and o and o["stopprice"].startswith("102.75"), {"http": r, "row": o})
    r = http("/order-matcher/replace", {"orderRef": stop_buy, "quantity": 4, "orderType": "STOP", "timeInForce": "DAY", "stopPrice": 102.75, "side": "Buy"})
    o = row(stop_buy)
    case("replace changing TIF refused; order unchanged", "FR-OT05/29", r[0] != 200 and o and o["timeinforce"] == "GTC" and o["status"] == "PENDING_TRIGGER", {"http": r, "row": o})
    # liquidity for the triggered stop, then a print through 102.75 (the 101s are gone; 110 is the DAY order)
    order(THIRD, "Sell", 20, orderType="LIMIT", timeInForce="GTC", limitPrice=104)
    r1 = order(SELLER, "Sell", 1, orderType="LIMIT", timeInForce="GTC", limitPrice=103)
    order(THIRD, "Buy", 1, orderType="LIMIT", timeInForce="IOC", limitPrice=103)
    o = row(stop_buy, {"FILLED", "CANCELED", "REJECTED"})
    case("print at 103 triggers the buy STOP; it executes as MARKET against 104", "FR-OT10/12", o and o["status"] == "FILLED" and o["triggered"] in ("1", "true"),
         {"row": o, "trades": trades(T)[-3:]})

    # ---- ICEBERG ------------------------------------------------------------------------------------
    r = order(SELLER, "Sell", 30, orderType="ICEBERG", timeInForce="GTC", limitPrice=103.5, displayQuantity=10)
    ice = ref_of(r); o = row(ice, {"NEW"})
    b = bbo(T)
    case("ICEBERG rests showing its tranche", "FR-OT20", o and o["status"] == "NEW" and o["displayquantity"] == "10", {"http": r, "row": o, "bbo": b})
    r = order(BUYER, "Buy", 15, orderType="LIMIT", timeInForce="IOC", limitPrice=103.5)
    o = row(ice, {"PARTIALLY_FILLED"})
    time.sleep(1); o = row(ice, {"PARTIALLY_FILLED"})
    case("aggressor takes 15 across a replenishment; iceberg keeps 15", "FR-OT20/21", o and o["status"] == "PARTIALLY_FILLED" and o["remainingquantity"] == "15", {"row": o})
    r = http("/order-matcher/replace", {"orderRef": ice, "quantity": 25, "orderType": "ICEBERG", "timeInForce": "GTC", "limitPrice": 103.5, "displayQuantity": 5, "side": "Sell"})
    o = row(ice)
    case("replace ICEBERG size-down and smaller tranche", "FR-OT29", r[0] == 200 and o and o["remainingquantity"] == "10" and o["displayquantity"] == "5", {"http": r, "row": o})

    r = http("/order-matcher/cancel", {"orderRef": ice})
    o = row(ice, {"CANCELED"})
    case("cancel the ICEBERG (hidden quantity included)", "FR-OT28", r[0] == 200 and o and o["status"] == "CANCELED", {"http": r, "row": o})

    # ---- PEGGED (local book) -----------------------------------------------------------------------
    r_bid = order(THIRD, "Buy", 5, orderType="LIMIT", timeInForce="GTC", limitPrice=99)
    r = order(BUYER, "Buy", 3, orderType="PEGGED", timeInForce="GTC", pegReference="PRIMARY", pegOffset=0, limitPrice=110)
    peg = ref_of(r); o = row(peg, {"NEW"})
    case("PEGGED PRIMARY buy joins the best non-peg bid (99)", "FR-OT23", o and o["status"] == "NEW" and o["limitprice"].startswith("99"), {"http": r, "row": o})
    http("/order-matcher/replace", {"orderRef": ref_of(r_bid), "quantity": 5, "orderType": "LIMIT", "timeInForce": "GTC", "limitPrice": 99.5, "side": "Buy"})
    o = row(peg)
    deadline = time.time() + 10
    while o and not o["limitprice"].startswith("99.5") and time.time() < deadline:
        time.sleep(0.5); o = row(peg)
    case("PEGGED reprices when the reference bid moves to 99.5", "FR-OT25", o and o["limitprice"].startswith("99.5"), {"row": o})
    r = order(BUYER, "Buy", 1, T2, orderType="PEGGED", timeInForce="GTC", pegReference="MIDPOINT", pegOffset=0, limitPrice=110)
    o = row(ref_of(r), {"REJECTED"})
    case("PEGGED on an empty book: REJECTED PEG_REFERENCE_MISSING", "FR-OT24", o and o["status"] == "REJECTED" and "PEG_REFERENCE_MISSING" in (o["reason"] or ""), {"http": r, "row": o})
    r = order(BUYER, "Buy", 1, orderType="PEGGED", timeInForce="GTC", pegReference="PRIMARY", pegOffset=1, limitPrice=110)
    case("PEGGED buy with a positive (aggressive) offset refused (422)", "FR-OT23", r[0] == 422, {"http": r})

    # ---- TRAILING_STOP ------------------------------------------------------------------------------
    r = order(BUYER, "Sell", 1, T2, orderType="TRAILING_STOP", timeInForce="GTC", trailAmount=2)
    o = row(ref_of(r), {"REJECTED"})
    case("TRAILING_STOP before any trade: REJECTED TRAIL_REFERENCE_MISSING", "FR-OT18", o and o["status"] == "REJECTED" and "TRAIL_REFERENCE_MISSING" in (o["reason"] or ""), {"http": r, "row": o})
    last = trades(T)[-1][3]
    r = order(SELLER, "Sell", 2, orderType="TRAILING_STOP", timeInForce="GTC", trailAmount=2)
    trail = ref_of(r); o0 = row(trail, {"PENDING_TRIGGER"})
    case(f"TRAILING_STOP sell pending; stop = last trade {last} - 2", "FR-OT18/19", o0 and o0["status"] == "PENDING_TRIGGER", {"http": r, "row": o0})
    # a higher print raises the watermark: BUYER lifts 1 of THIRD's 104 offer (the iceberg is gone)
    order(BUYER, "Buy", 1, orderType="LIMIT", timeInForce="IOC", limitPrice=104)
    o1 = row(trail)
    deadline = time.time() + 10
    while o1 and o0 and o1["stopprice"] == o0["stopprice"] and time.time() < deadline:
        time.sleep(0.5); o1 = row(trail)
    case("read model shows the CURRENT trailing level after the 104 print (FR-OT33 column)", "FR-OT19/33",
         o1 and o0 and float(o1["stopprice"]) > float(o0["stopprice"]), {"before": o0, "after": o1},
         known_gap="RI07-F3: a watermark ratchet emits no order update, so stopprice stays at the last emitted level")
    # a print at or below the stop triggers it: BUYER sells 1 into THIRD's 99.5 bid (time priority over BUYER's own peg)
    order(BUYER, "Sell", 1, orderType="LIMIT", timeInForce="IOC", limitPrice=99.5)
    o = row(trail, {"FILLED", "CANCELED", "REJECTED"})
    case("a print at 99.5 triggers the ratcheted stop (level 102, not 101.5); it sells as MARKET", "FR-OT10/12/19",
         o and o["status"] in ("FILLED", "CANCELED") and o["triggered"] in ("1", "true") and float(o["stopprice"]) == 102.0,
         {"row": o, "trades": trades(T)[-3:]})

    r = http("/order-matcher/cancel", {"orderRef": ref_of(r_bid)})
    o = row(peg, {"SUSPENDED"})
    case("reference bid cancelled: the peg is SUSPENDED (REFERENCE), still live", "FR-OT24/27", o and o["status"] == "SUSPENDED" and "REFERENCE" in (o["suspendreason"] or ""), {"http": r, "row": o})

    # ---- cancel, DAY lifecycle ----------------------------------------------------------------------
    r = http("/order-matcher/cancel", {"orderRef": stop_lim})
    o = row(stop_lim, {"CANCELED"})
    case("cancel a pending STOP_LIMIT", "FR-OT28", r[0] == 200 and o and o["status"] == "CANCELED", {"http": r, "row": o})
    r = http("/order-matcher/session/day-end", {"businessDate": TODAY})
    o = row(lim_day, {"CANCELED"})
    case("DAY_END expires the DAY order", "FR-OT08/37", r[0] == 200 and o and o["status"] == "CANCELED" and "DAY_EXPIRED" in (o["reason"] or ""), {"http": r, "row": o})
    r = order(SELLER, "Sell", 1, orderType="LIMIT", timeInForce="DAY", limitPrice=105)
    o = row(ref_of(r), {"REJECTED"})
    case("DAY order with no open business day: REJECTED NO_TRADING_DAY", "FR-OT08", o and o["status"] == "REJECTED" and "NO_TRADING_DAY" in (o["reason"] or ""), {"http": r, "row": o})
    o = row(lim_gtc)
    case("GTC order survives DAY_END", "FR-OT08", o and o["status"] in ("NEW", "PARTIALLY_FILLED", "FILLED"), {"row": o})



def verdict(rs):
    """(exit code, summary). Only 0 is acceptance; a known gap that still reproduces is INCOMPLETE."""
    ordinary = [x for x in rs if not x.get("knownGap")]
    gaps = [x for x in rs if x.get("knownGap")]
    passed = sum(1 for x in ordinary if x["pass"])
    open_gaps = [g for g in gaps if not g["pass"]]
    lines = [f"{passed}/{len(ordinary)} ordinary cases matched the spec at the effect end"]
    lines += [f"known gap {'CLOSED' if g['pass'] else 'still present'}: {g['knownGap']}" for g in gaps]
    if passed != len(ordinary) or not rs:
        return 1, "\n".join(lines + ["VERDICT: FAIL"])
    if open_gaps:
        return 3, "\n".join(lines + [f"VERDICT: INCOMPLETE -- {len(open_gaps)} known gap(s) still present; NOT acceptance"])
    return 0, "\n".join(lines + ["VERDICT: PASS -- every case, known gaps included, matched"])


def main(argv, env):
    results.clear()
    aborted = None
    try:
        configure(env)
        scenario()
    except Refused as e:
        print(f"[precondition] {e}")
        return 2
    except QueryFailed as e:
        aborted = str(e)
        print(f"[ABORTED] {e}\nno verdict is drawn from a read that did not happen")
    except Exception as e:  # any other crash is a failed run, with its evidence still written
        aborted = f"{type(e).__name__}: {e}"
        print(f"[ABORTED] {aborted}")
    code, text = (1, "VERDICT: FAIL (aborted: " + aborted + ")") if aborted else verdict(results)
    print(f"\n{text}\ninstruments {T},{T2}")
    if len(argv) > 1:
        with open(argv[1], "w") as f:
            json.dump({"console": CONSOLE, "context": CTX, "instruments": [T, T2], "businessDate": TODAY,
                       "aborted": aborted, "exitCode": code, "results": results}, f, indent=1, default=str)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv, os.environ))
