/ hsb.q: KDB-X history store for the bake-off, the intraday write-down pattern.
/ HistoryFeed (sink=kdb) calls upd[table;partition;batch] over IPC. Batches land in small in-memory tables;
/ every second wd appends them to the date-partitioned splayed tables under DB, syncs the filesystem, and only
/ then advances the durable watermark the feed acks to. An HDB process (q DB) serves the queries.
/ Run: HSB_DB=/data/kdb/db q hsb.q -p 5010 -s 4

DB:hsym `$getenv`HSB_DB;
trades:([] ts:`timestamp$(); seq:`long$(); part:`short$(); tradeSeq:`long$(); side:`char$(); account:`int$();
  security:`symbol$(); qty:`int$(); px:`float$(); orderRef:`long$(); cseq:`long$());
orders:([] ts:`timestamp$(); seq:`long$(); part:`short$(); orderRef:`long$(); account:`int$(); security:`symbol$();
  side:`char$(); qty:`int$(); remaining:`int$(); limitPx:`float$(); status:`symbol$(); lastPx:`float$();
  lastQty:`int$(); created:`timestamp$(); cseq:`long$(); ordinal:`int$(); clientKey:`long$());
E:{0#value x} each `trades`orders!`trades`orders;   / empty schemas
K:{[t;p] `$string[t],"_",string p};
MX:(`$())!`long$();   / max seq received, per table_partition (memory + disk)
DUR:(`$())!`long$();  / max seq written down and synced

/ Recover both watermarks from disk: every date partition's seq and part columns.
dates:{x where x like "[0-9][0-9][0-9][0-9].[0-9][0-9].[0-9][0-9]"} string key DB;
recover:{[d;t] f:` sv DB,d,t;
  if[not ()~key f; s:get ` sv f,`seq; p:get ` sv f,`part;
    m:exec max s by p from ([] s;p);
    {[t;m;p] k:K[t;p]; MX[k]::MX[k]|m p; DUR[k]::MX k}[t;m] each key m]};
if[not ()~key DB; recover .' (`$dates) cross `trades`orders];

res:{[t;p] 0^MX K[t;p]};
dur:{[t;p] 0^DUR K[t;p]};
upd:{[t;p;x]
  x:update part:p, ts:`timestamp$ts from x;
  if[`created in cols x; x:update created:`timestamp$created from x];
  t insert cols[t]#x;
  MX[K[t;p]]:last x`seq;
  dur[t;p]};

wd:{
  if[0=count[trades]+count orders; :()];
  m:MX;
  {[t] v:value t;
    if[count v;
      {[t;v;d] (` sv DB,(`$string d),t,`) upsert .Q.en[DB] select from v where d=`date$ts}[t;v] each distinct `date$v`ts;
      t set E t]} each `trades`orders;
  system "sync -f ",1_string DB;   / fsync the filesystem before the watermark moves
  DUR::m;
  };
.z.ts:wd;
\t 1000
