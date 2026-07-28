// txstore.q -- KDB-X analytical view over a captured TraderX SESSION: our own orders and
// our own executions, as taken off the cluster by the leader-side off-consensus tap.
//
// NAMING. tickstore.q loads the NYSE TAQ tape as `quote` and `trade`. Those are the market's
// prints. THIS file loads OUR matching engine's flow as `txOrder` and `txTrade`. A tape trade
// and an engine execution are different objects with different provenance, so they get
// different names -- one `trade` table holding both is exactly the mistake that makes a VWAP
// silently answer the wrong question. The two stores are loadable side by side.
//
//   quote / trade      NYSE TAQ tape       tickstore.q     what the market did
//   txOrder / txTrade  TraderX cluster     txstore.q       what OUR engine did
//
// ANALYTICAL STORE ONLY. The capture log this reads is NOT the Aeron Archive consensus
// journal. The Archive is authoritative: it is the deterministic replay source for recovery,
// it is synchronous and on the hot path, and it is untouched by any of this. The capture is
// off-consensus, best-effort, leader-side and lossy under flood BY DESIGN (the tap drops and
// says so rather than slowing apply). "Playback" here means replaying a captured session for
// analysis; it never rebuilds cluster state.
//
// Usage:  TXSTORE_DIR=/path/to/kdb-capture q txstore.q
// Layout: <dir>/txorder-<epoch>-<member>.csv, <dir>/txtrade-<epoch>-<member>.csv
//         Written by KdbTapWriter (order-matcher, cluster package). Only the leader writes,
//         but a member that led earlier has its own file, so all files load together.

.tx.dir:$[count d:getenv`TXSTORE_DIR;d;'"TXSTORE_DIR not set"];

// Column types, positional against KdbTapWriter.ORDER_HEADER / TRADE_HEADER. The header row in
// the file names the columns (that is what `enlist","` means to 0:), so a column added on the
// Java side without a change here fails loudly at load instead of shifting every value right.
.tx.ORDER_TYPES:"JSJJSCJJFSFJJJ";
.tx.TRADE_TYPES:"JSJJSCJFJ";

.tx.ls:{string key hsym`$x};
// q is right-to-left: the parens matter. Without them the each-right binds to "/" and every
// path comes out as "/<file>" -- the same class of trap as 0.5*gap>x in tickstore.q.
.tx.files:{[pfx] f:.tx.ls .tx.dir; (.tx.dir,"/"),/:f where f like pfx,"*.csv"};
.tx.read:{[types;f] (types;enlist",") 0: hsym`$f};
.tx.ms:{1970.01.01D0+1000000j*x};   // cluster-clock epoch millis -> timestamp

.tx.loadKind:{[types;pfx]
  fs:.tx.files pfx;
  if[0=count fs;'"no ",pfx," capture files under ",.tx.dir];
  raze .tx.read[types]each fs};

.tx.load:{[]
  // seq is the consensus sequence number of the input that produced the row, so it is a total
  // order over the session and the right sort key; ts is cluster time, which is coarser.
  txOrder::`seq xasc update ts:.tx.ms updatedMs, created:.tx.ms createdMs
    from .tx.loadKind[.tx.ORDER_TYPES;"txorder"];
  txTrade::`seq xasc update ts:.tx.ms tsMs from .tx.loadKind[.tx.TRADE_TYPES;"txtrade"];
  -1"txstore: ",string[count txOrder]," order events + ",string[count txTrade],
    " executions over ",string[count distinct txOrder`sym]," symbol(s), epoch(s) ",
    (", "sv string distinct txOrder`epoch),".";
  if[count txOrder;
    -1"txstore: seq ",string[first txOrder`seq]," .. ",string[last txOrder`seq],", captured ",
      string[`second$(last txOrder`ts)-first txOrder`ts]," of session wall time.";];};

// ---------------------------------------------------------------- analytics

// .tx.fills[] -- what actually executed, per symbol. This is OUR fill VWAP: unlike the tape
// VWAP in tickstore.q it is complete and unconditioned, because the engine emitted every one
// of these itself. It IS subject to capture loss under flood -- check .tx.gaps[] first.
.tx.fills:{[]
  0!select execs:count i, volume:sum qty, vwap:(sum px*qty)%sum qty,
           first_px:first px, last_px:last px by sym from txTrade};

// .tx.orders[] -- final state per order. Keyed on (epoch;ref), never ref alone: orderRef
// restarts at 1 on a fresh cluster incarnation, so a bare ref silently merges two orders.
.tx.orders:{[]
  0!select events:count i, last status, last remaining, qty:last qty, sym:last sym,
           account:last account, side:last side, filled:last[qty]-last remaining
    by epoch,ref from txOrder};

// .tx.gaps[] -- capture integrity. The tap drops rather than blocking apply, so a session can
// be a sample rather than a census. Consensus seq numbers are contiguous for consecutive
// applies, so a hole in the captured seq range is a dropped (or non-order) event. This is the
// honest counterpart to the tap's WARN: never present an aggregate over this store without it.
.tx.gaps:{[]
  s:asc distinct txOrder[`seq],txTrade`seq;
  if[2>count s;:([]from:();to:();missing:())];
  d:1_deltas s;
  i:where d>1;
  ([]from:s i;to:s i+1;missing:d[i]-1)};

// ---------------------------------------------------------------- playback

// .tx.session[] -- one merged, time-ordered stream of the captured session: order lifecycle
// events and executions interleaved in consensus order. This is the analytical playback
// content -- a faithful record of what the engine decided, NOT a mechanism for rebuilding
// engine state (that is the Aeron Archive's job, and only its job).
.tx.session:{[]
  o:select seq,ts,kind:`order,sym,account,side,qty,px:limitPx,id:ref,status from txOrder;
  t:select seq,ts,kind:`exec,sym,account,side,qty,px,id:tradeSeq,status:`BOOKED from txTrade;
  `ts`seq xasc o,t};

// .tx.replay[stream;speed;cb] -- walk a session calling cb on each row, paced at `speed` x
// real time; 0w replays as fast as possible. Same pacing as .ts.replay for the tape, kept
// standalone on purpose: a captured session must load without the TAQ corpus present.
.tx.replay:{[s;speed;cb]
  s:0!s;
  w:0f,(`float$1_ deltas s`ts)%1e9*speed;
  {[cb;w;r] if[w>0.001; system"sleep ",string w]; cb r}[cb]'[w;s];
  count s};

.tx.load[];
