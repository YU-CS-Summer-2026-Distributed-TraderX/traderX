/ selfcheck.q -- fails if tickstore.q stops reading the corpus correctly.
//
/ Every expected value below was computed independently with DuckDB over the same
/ files, so this is a cross-implementation check, not kdb agreeing with kdb.
//
/ Usage:  TICKSTORE_ROOT=/path/to/sample q selfcheck.q
/ Sample: dt=2025-02-03 + 2025-02-04, symbols AAPL MSFT SPY CROX.

system"l tickstore.q";

.chk.n:0;
.chk.ok:{[nm;c] .chk.n+:1; $[c;-1"ok   ",nm;[-1"FAIL ",nm; exit 1]];};
.chk.eq:{[nm;a;b] .chk.ok[nm;a~b]; if[not a~b;-1"  got ",-3!a;-1"  want ",-3!b;]};

/ ---- 1. row counts per partition, after duplicate collapse -------------------
want:([]dt:(8#2025.02.03),8#2025.02.04;
        symbol:16#`AAPL`AAPL`CROX`CROX`MSFT`MSFT`SPY`SPY;
        kind:16#`quote`trade;
        n:4628445 851995 143918 43860 1835038 505751 26039159 732184,
          2528009 497343 98990 26665 1426743 354136 12171491 381993);
got:`dt`symbol`kind xasc `dt`symbol`kind`n#
  (update kind:`quote from 0!select n:count i by dt,symbol from quote),
   update kind:`trade from 0!select n:count i by dt,symbol from trade;
.chk.eq["per-partition row counts";got;want];
.chk.eq["deduped corpus total";exec sum n from got;52265720];

/ ---- 2. the duplicate really was a duplicate ---------------------------------
/ AAPL 2025-02-03 shipped two equal-sized quote files from a re-ingest. Loading both
/ gives 9256890 rows; collapsing to one gives exactly half.
.chk.eq["AAPL 2025-02-03 quotes de-duplicated";
  exec first n from got where dt=2025.02.03,symbol=`AAPL,kind=`quote;4628445];

/ ---- 3. the quote/trade split holds structurally -----------------------------
/ This is why the store is two tables: an unguarded price aggregate over a mixed
/ table silently folds NULL-priced quote rows into the answer.
.chk.ok["no priced rows in quote";0=count select from quote where dt=2025.02.03,symbol=`CROX,not null price];
.chk.ok["no quoted rows in trade";0=count select from trade where dt=2025.02.03,symbol=`CROX,not null bid_price];

/ ---- 4. values match DuckDB to the tick --------------------------------------
.chk.eq["CROX first two trades";
  `event_type`ts`price`size`venue`seq#0!select from trade
    where dt=2025.02.03,symbol=`CROX,ts<2025.02.03D04:00:00.043519;
  ([]event_type:("trade";"trade");
     ts:2025.02.03D04:00:00.043495000 2025.02.03D04:00:00.043518000;
     price:100.33 100.42; size:6 4; venue:enlist each "KK"; seq:2001 2002)];

/ ---- 5. regular-hours VWAP matches DuckDB ------------------------------------
/ Unfiltered prints: the ingest dropped TR_CORR/TR_SCOND, so corrected, cancelled and
/ non-last-eligible sales are still in here. This is correct arithmetic over what is
/ stored, not an official VWAP.
v:exec vwap from `symbol`dt xasc raze .ts.vwap[;`AAPL`CROX`MSFT`SPY;09:30:00.000000000;16:00:00.000000000]each 2025.02.03 2025.02.04;
.chk.ok["regular-hours VWAP vs DuckDB";
  all 1e-6>abs v-228.223367 230.866508 98.361238 97.801776 412.228641 411.99325 596.675203 600.598897];

/ ---- 6. playback is a deterministic, time-ordered merge ----------------------
s:.ts.session[2025.02.03;`CROX`MSFT;09:30:00.000000000;09:30:05.000000000];
w:{[t] count select from t where dt=2025.02.03,symbol in`CROX`MSFT,
   ts within(2025.02.03D09:30:00.000000000;2025.02.03D09:30:05.000000000)};
.chk.ok["session is non-empty";0<count s];
.chk.ok["session is ts-ordered";(exec ts from s)~asc exec ts from s];
.chk.ok["session merges both kinds";2=count distinct exec event_type from s];
.chk.ok["session merges both symbols";2=count distinct exec symbol from s];
.chk.eq["session ~ sum of its windows";count s;w[quote]+w trade];

/ ---- 7. replay delivers every row, in order ----------------------------------
seen:();
.chk.eq["replay delivered every row";.ts.replay[5#s;0w;{seen,:enlist x`ts}];5];
.chk.eq["replay preserved order";seen;5#exec ts from s];

/ speed 0w above skips the pacing branch entirely, so exercise it once on a window with
/ a real gap in it. Lower bound only -- this asserts the sleep happened, and deliberately
/ makes no claim about how fast this machine is.
p:.ts.session[2025.02.03;`CROX;04:00:00.000000000;04:00:00.050000000];
gap:(`float$sum 1_ deltas exec ts from p)%1e9;
.chk.ok["pacing window has a sleepable gap";gap>0.02];
t0:.z.p; .ts.replay[p;1.0;{}]; el:(`float$.z.p-t0)%1e9;
.chk.ok["replay at speed 1.0 paces to real time";el>0.8*gap];
t0:.z.p; .ts.replay[p;0w;{}]; .chk.ok["replay at speed 0w does not pace";(0.5*gap)>(`float$.z.p-t0)%1e9];

-1"";
-1"selfcheck: ",string[.chk.n]," checks passed.";
exit 0
