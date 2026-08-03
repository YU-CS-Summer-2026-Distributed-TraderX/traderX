/ tickstore.q -- KDB-X analytical view over the TAQ tick-store Parquet corpus.
//
/ ANALYTICAL STORE ONLY. This is not the Aeron Archive consensus journal: nothing
/ here is authoritative, on the hot path, or required for recovery. "Playback" here
/ means replaying a captured market session for analysis, not the deterministic
/ consensus replay that rebuilds cluster state. See README.md.
//
/ Usage:  TICKSTORE_ROOT=/path/to/ticks q tickstore.q
/ Layout: <root>/dt=YYYY-MM-DD/symbol=SYM/<uuid>.parquet  (ZSTD)
//
/ There is no conversion step. KDB-X reads the ZSTD Parquet natively and maps it as
/ a date/symbol-partitioned virtual table, so the store IS the corpus in GCS.

.pq:use`kx.pq;
.pqt:use`kx.pq.t;

.ts.root:$[count r:getenv`TICKSTORE_ROOT;r;'"TICKSTORE_ROOT not set"];

.ts.ls:{string key hsym`$x};

/ Literal two-level walk of dt=/symbol=. Deliberately NOT a recursive glob: the full
/ corpus is ~10,100 symbol partitions per day (~400k objects) and a glob LISTs every
/ one of them before any predicate prunes. Narrow paths only.
.ts.scan:{[root]
  fs:raze raze {[r;d] {[r;d;s] p:r,"/",d,"/",s; (p,"/",)each .ts.ls p}[r;d]each .ts.ls r,"/",d}[root;]each .ts.ls root;
  p:{last each"="vs/:-2#-1_"/"vs x}each fs;
  ([] path:fs; dt:"D"$p[;0]; symbol:`$p[;1]; bytes:hcount each hsym`$fs)};

/ event_type discriminates quote from trade rows, and the ingest wrote the two kinds
/ to separate files. Classify each file by reading one value -- one column chunk of
/ row group 0, not the file.
.ts.kind:{[f] `$first exec event_type from .pq.pq hsym`$f};

/ Re-ingest wrote NEW uuid-named files alongside the old ones (OVERWRITE_OR_IGNORE),
/ so a (dt;symbol;kind) group can hold byte-identical-sized duplicates whose rows
/ differ only in ingested_at. Collapse those to one file per distinct size.
/ An UNEQUAL-sized pair within one group is NOT a duplicate -- it is a partial or
/ truncated write (the 2025-03-11 OOM class). Those are kept and reported loudly
/ rather than silently dropped or silently double-counted.
.ts.dedup:{[t]
  t:update kind:.ts.kind each path from t;
  g:0!select path:first path, dropped:1_path, sizes:distinct bytes, nfile:count i by dt,symbol,kind,bytes from t;
  d:exec raze dropped from g where 0<count each dropped;
  if[count d; -1"tickstore: collapsed ",string[count d]," re-ingest duplicate file(s):"; -1"  ",/:d;];
  k:0!select nsize:count distinct bytes by dt,symbol,kind from t;
  if[count s:select from k where nsize>1;
    -1"tickstore: WARNING -- unequal-sized files in one (dt;symbol;kind) group. This is a";
    -1"tickstore: partial/truncated write, not a duplicate. Both are loaded; verify before use:";
    show s;];
  select path,dt,symbol,kind from g};

/ Two partitioned virtual tables rather than one. Quote rows carry NULL price/size and
/ trade rows carry NULL bid/ask, so a single table makes every price aggregate one
/ forgotten WHERE away from being wrong. Splitting removes that by construction.
.ts.mk:{[t] .pqt.mkP (select dt,symbol from t)!(.pq.pq each hsym`$t`path)};

.ts.load:{[]
  f:.ts.dedup .ts.scan .ts.root;
  quote::.ts.mk select from f where kind=`quote;
  trade::.ts.mk select from f where kind=`trade;
  -1"tickstore: ",string[count f]," files -> quote + trade over ",
    string[count distinct f`dt]," date(s), ",string[count distinct f`symbol]," symbol(s).";};

/ ---------------------------------------------------------------- analytics

/ .ts.vwap[dt;syms;t0;t1] -- volume-weighted average price over a time-of-day window.
/ Unfiltered prints: the ingest dropped TR_CORR and TR_SCOND, so corrected, cancelled
/ and non-last-eligible sales are still in the numbers. This is correct arithmetic over
/ what is stored, not an official VWAP.
/ One date per call on purpose: the virtual-table query engine rejects the each-both
/ form (ts within'(dt+t0;dt+t1)) that would vectorise this across dates. Iterate dates.
.ts.vwap:{[d;syms;t0;t1]
  0!select vwap:(sum price*size)%sum size, volume:sum size, trades:count i by symbol,dt
    from trade where dt=d, symbol in syms, ts within(d+t0;d+t1)};

/ .ts.spread[dt;syms;t0;t1] -- quoted spread. Zero-sided quotes are real values in this
/ corpus (pre-open placeholders), not nulls, so they are excluded explicitly.
.ts.spread:{[d;syms;t0;t1]
  0!select avg_spread:avg ask_price-bid_price, min_spread:min ask_price-bid_price,
           max_spread:max ask_price-bid_price, quotes:count i by symbol,dt
    from quote where dt=d, symbol in syms, ts within(d+t0;d+t1), bid_price>0, ask_price>0};

/ ---------------------------------------------------------------- playback

/ .ts.session[dt;syms;t0;t1] -- one merged, ts-ordered quote+trade stream for a window.
/ This is the whole correctness content of analytical playback: a deterministic,
/ time-ordered view of what the market did. t0/t1 are times of day.
.ts.session:{[d;syms;t0;t1]
  w:{[t;d;s;a;b] select dt,symbol,event_type,ts,price,size,bid_price,bid_size,ask_price,ask_size,venue,seq
      from t where dt=d, symbol in s, ts within (d+a;d+b)};
  `ts xasc (w[quote;d;syms;t0;t1]),w[trade;d;syms;t0;t1]};

/ .ts.replay[stream;speed;cb] -- walk a session calling cb on each row, paced at
/ `speed` x real time. speed=0w replays as fast as possible. Sub-millisecond gaps are
/ not slept on; this paces a demo, it does not reproduce inter-tick latency.
.ts.replay:{[s;speed;cb]
  s:0!s;
  w:0f,(`float$1_ deltas s`ts)%1e9*speed;
  {[cb;w;r] if[w>0.001; system"sleep ",string w]; cb r}[cb]'[w;s];
  count s};

.ts.load[];
