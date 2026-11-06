#!/usr/bin/env bash
# FR-THT-04 open question, answered by a real read: can KDB-X Community serve an HDB date segment from gs://?
# Builds four account-sorted p# dates in two segments, uploads segment B to $THT_GS (a gs:// prefix you own),
# and compares every row of an all-local root with a root whose par.txt names the gs:// copy.
#   PASS  local == gs (whole table, row for row)
#   NEG1  a gs segment with one qty changed must NOT match (the comparison can fail)
#   NEG2  the same root without objstor init must load the gs dates as absent, silently: the reason the
#         HDB guard counts dates instead of trusting a clean load
# Needs: q (KDB-X, objstor module), gcloud signed in with write on $THT_GS, GCLOUD_PROJECT_ID.
set -euo pipefail
: "${THT_GS:?set THT_GS=gs://bucket/prefix}" "${GCLOUD_PROJECT_ID:?set GCLOUD_PROJECT_ID}"
export GCLOUD_PROJECT_ID
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT; cd "$W"; mkdir rootL rootG rootN rootX
cat > build.q <<'Q'
mk:{[seg;d;n] system "S ",string 7+`int$d;
  t:`account xasc ([] account:`int$100001+n?50; time:asc 09:30:00.000+n?23400000; sym:n?`AAPL`MSFT`IBM;
    side:n?`B`S; qty:`int$100*1+n?20; px:0.01*floor 100*100+n?50f);
  .Q.dd[hsym`$seg;d,`trades`] set .Q.en[`:rootL] update `p#account from t};
mk["segA";2026.11.02;20000]; mk["segA";2026.11.03;20000]; mk["segB";2026.11.04;20000]; mk["segB";2026.11.05;20000];
/ the mutant: one qty changed on the last date
system "cp -R segB segX"; v:get `:segX/2026.11.05/trades/qty; v[0]+:1i; `:segX/2026.11.05/trades/qty set v;
exit 0
Q
q build.q -q
gcloud storage cp -r --no-user-output-enabled segB segX "$THT_GS/"
for r in rootG rootN rootX; do cp rootL/sym $r/; done
printf '%s\n' "$W/segA" "$W/segB" > rootL/par.txt
printf '%s\n' "$W/segA" "$THT_GS/segB" > rootG/par.txt
printf '%s\n' "$W/segA" "$THT_GS/segB" > rootN/par.txt
printf '%s\n' "$W/segA" "$THT_GS/segX" > rootX/par.txt
cat > dump.q <<'Q'
if[not "noinit"~.z.x 1; os:use`kx.objstor; os[`init][]];
o:hsym`$.z.x 2; system "l ",.z.x 0; o set select from trades; exit 0
Q
for r in rootL rootG rootX; do q dump.q $r init "$W/$r.out" -q; done
q dump.q rootN noinit "$W/rootN.out" -q
cat > cmp.q <<'Q'
L:get`:rootL.out; G:get`:rootG.out; X:get`:rootX.out; N:get`:rootN.out;
-1 "local ",string[count L]," rows, ",string[count distinct L`date]," dates; gs ",string[count G]," rows";
ok:(L~G) and (count distinct L`date)=4;
neg1:not L~X; neg2:(not L~N) and 2=count distinct N`date;
-1 "PASS local == gs: ",string ok; -1 "NEG1 mutant differs: ",string neg1; -1 "NEG2 no-init drops gs dates silently: ",string neg2;
exit `int$not ok and neg1 and neg2
Q
q cmp.q -q
