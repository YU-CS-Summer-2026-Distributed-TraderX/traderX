/ demo-hdb.q (FR-DA10): one account's trade history in KDB-X, queried live from the console's Kdb tab.
/ First run builds a date-partitioned HDB of N trades (default 5,000,000) over a year of weekdays for ACCOUNT
/ (default 99000001), deterministic (fixed seed); later runs load it. Then it answers POST /q {"q":"..."} with
/ {columns, rows (first 2000), count, ms} or {error, ms}. Every query runs under reval: read-only, no system commands.
/   q demo-hdb.q -p 5050 [-db DIR ($HOME/traderx-demo-hdb)] [-n 5000000] [-account 99000001]
o:.Q.opt .z.x;
arg:{[k;d] $[k in key o; first o k; d]};
DB:hsym `$arg[`db;getenv[`HOME],"/traderx-demo-hdb"];   / FR-DA12: durable by default; the VM service passes /var/lib/traderx-kdb/hdb
N:"J"$arg[`n;"5000000"];
ACCT:"I"$arg[`account;"99000001"];
SYMS:`AAPL`MSFT`AMZN`NVDA`GOOGL`META`TSLA`JPM`V`XOM`UNH`JNJ`WMT`PG`MA`HD`CVX`KO`PEP`BAC;
BASE:190 415 185 135 170 560 250 230 290 115 520 155 85 165 520 390 150 62 170 44f;
DAYS:{d where 1<(d:x+til y) mod 7}[2025.11.03;365];   / weekdays (2000.01.01 was a Saturday)

build:{
  -1 "building ",string[N]," trades for account ",string[ACCT]," over ",string[count DAYS]," days into ",1_string DB;
  system "S 20261105";
  nd:count DAYS; ns:count SYMS;
  lvl:BASE*/:exp sums (nd;ns)#0.02*-0.5+(nd*ns)?1f;   / one random-walk close per day per symbol
  per:(nd#N div nd)+(til nd)<N mod nd;
  `id set 0;
  {[lvl;per;ns;i]
    n:per i; s:n?ns;
    `trades set ([] time:asc 09:30:00.000+n?23400000; sym:SYMS s; side:n?`B`S; qty:`int$100*1+n?20;
      px:0.01*floor 0.5+100*lvl[i;s]*1+0.006*-0.5+n?1f; account:n#ACCT; tradeId:id+til n);
    id+::n;
    .Q.dpft[DB;DAYS i;`sym;`trades]}[lvl;per;ns] each til nd;
  -1 "built";
  };
if[()~key DB; build[]];
system "l ",1_string DB;
-1 "loaded ",string[count trades]," trades, ",string[count date]," days";

MAXROWS:2000;
shape:{[r]
  if[99h=type r; r:$[98h=type key r; 0!r; ([] k:key r; v:value r)]];
  if[98h<>type r; r:([] result:$[0>type r; enlist r; r])];
  t:MAXROWS sublist r;
  `columns`rows`count!(cols t; flip value flip t; count r)};
http:{"HTTP/1.1 ",x,"\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n",.j.j y};
.z.pp:{[x]
  q:@[{(.j.k x)`q};(1+x[0]?" ")_x 0;{""}];
  if[not 10h=type q; :http["400 Bad Request";enlist[`error]!enlist"want {\"q\":\"...\"}"]];
  t0:.z.p; r:@[{(1b;reval parse x)};q;{(0b;x)}]; ms:(.z.p-t0)%1e6;
  http["200 OK";$[r 0; shape[r 1],enlist[`ms]!enlist ms; `error`ms!(r 1;ms)]]};
.z.ph:{http["404 Not Found";enlist[`error]!enlist"POST /q only"]};
\T 10
