// txselfcheck.q -- fails if txstore.q stops reading a TraderX session capture correctly.
//
// The fixture under fixtures/session-yu13 was WRITTEN BY THE CLUSTER, not by hand: it is the
// output of AeronClusterSpikeTest.leaderTapCapturesTheAppliedSessionForKdb, a real single-member
// Aeron cluster applying real consensus ingress, with the leader-side tap attached. That Java
// test asserts the same session against the engine's own trade counter, so the numbers below are
// cross-checked against the deterministic engine rather than against kdb agreeing with kdb.
// Regenerate it with:
//   KDB_CAPTURE_FIXTURE_DIR=<this dir>/fixtures/session-yu13 ./gradlew test --tests '*Spike*'
//
// Usage:  q txselfcheck.q                       (the committed fixture)
//         TXSTORE_DIR=/path/to/capture q txselfcheck.q   (a live capture off a member's volume)

if[0=count getenv`TXSTORE_DIR; `TXSTORE_DIR setenv "fixtures/session-yu13"];
system"l txstore.q";

.chk.n:0;
.chk.ok:{[nm;c] .chk.n+:1; $[c;-1"ok   ",nm;[-1"FAIL ",nm; exit 1]];};
.chk.eq:{[nm;a;b] .chk.ok[nm;a~b]; if[not a~b;-1"  got ",-3!a;-1"  want ",-3!b;]};

if[not "fixtures/session-yu13"~getenv`TXSTORE_DIR;
  -1"txselfcheck: TXSTORE_DIR overridden -- shape checks only, fixture values skipped.";
  .chk.ok["loaded a capture";0<count txOrder];
  .chk.ok["seq order holds";txOrder~`seq xasc txOrder];
  -1"txselfcheck: ",string[.chk.n]," checks passed.";
  exit 0];

// ---- 1. the session loaded exactly ------------------------------------------
.chk.eq["order events";count txOrder;4];
.chk.eq["executions";count txTrade;2];
.chk.eq["one epoch";distinct txOrder`epoch;1#`9];

// ---- 2. the two tables are OUR flow, not the tape ---------------------------
// txTrade rows are matching-engine executions: every one carries an account, which a TAQ tape
// print never does. This is the whole reason the tables are not called trade/quote.
.chk.ok["every execution names an account";0=count select from txTrade where null account];
.chk.ok["every execution names a booking side";all txTrade[`side]in "BS"];

// ---- 3. a cross books BOTH sides --------------------------------------------
// The engine emits KIND_TRADE_BOOKED once per side of a match. Losing one side would halve
// every volume number in the store, so it is asserted rather than assumed.
.chk.eq["both sides booked";asc txTrade`side;"BS"];
.chk.eq["equal quantity both sides";1=count distinct txTrade`qty;1b];
.chk.eq["one price for the cross";distinct txTrade`px;1#100f];
.chk.eq["execution volume";exec sum qty from txTrade;20];

// ---- 4. analytics ------------------------------------------------------------
.chk.eq["fill vwap";exec first vwap from .tx.fills[];100f];
.chk.eq["orders fully filled";exec distinct status from .tx.orders[];1#`FILLED];
.chk.eq["filled quantity per order";exec distinct filled from .tx.orders[];1#10];

// ---- 5. capture integrity ----------------------------------------------------
// The tap drops rather than blocking apply, so a capture CAN be a sample. This fixture is a
// complete one and .tx.gaps must say so -- a gaps table that cannot detect a hole is worthless.
.chk.eq["no capture gaps";count .tx.gaps[];0];
.chk.eq["consensus order preserved";txOrder`seq;5 6 6 6];

// ---- 6. playback --------------------------------------------------------------
// Analytical playback: the merged, time-ordered session. NOT consensus replay -- nothing here
// rebuilds engine state; the Aeron Archive remains the only thing that does.
s:.tx.session[];
.chk.eq["session merges orders and executions";count s;6];
.chk.eq["session kinds";asc distinct s`kind;`exec`order];
.chk.ok["session is time ordered";s~`ts`seq xasc s];
.chk.eq["replay walks every row";.tx.replay[s;0w;{}];6];

// A .chk.eq called with two arguments is a PROJECTION in q: it builds a function, runs no
// assertion, prints nothing and passes. This total is the guard against that -- bump it when
// you add a check. It caught exactly that typo on the first run of this file.
if[18<>.chk.n; -1"FAIL expected 18 checks, ran ",string .chk.n; exit 1];
-1"txselfcheck: ",string[.chk.n]," checks passed.";
// Explicit: without it q falls into its REPL and a CI gate hangs instead of passing.
exit 0;
