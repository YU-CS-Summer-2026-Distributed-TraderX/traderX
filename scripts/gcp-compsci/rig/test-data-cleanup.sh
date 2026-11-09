#!/usr/bin/env bash
# FR-PRG-38: clear the load test accounts' rows from production's read model. Never run without yaakov's yes.
#
#   scripts/gcp-compsci/rig/test-data-cleanup.sh plan
#       read-only: per archived epoch (arc_<label>_*) and the live epoch, each account table's rows on the test accounts,
#       the accounts' directory rows, Secret fix-test-clients and the member-disk snapshots. Changes nothing.
#   scripts/gcp-compsci/rig/test-data-cleanup.sh apply LABEL [LABEL...] [--accounts] [--secret] [--dry-run]
#       deletes the test accounts' rows from arc_<LABEL>_{orderbook,order_view_snapshot,trades,positions} in one
#       transaction per label. --accounts also deletes their accounts/accountusers rows, refused while any table, live
#       or archived, still holds a row of theirs. --secret deletes Secret fix-test-clients (render without
#       --fix-test-clients afterwards, or the gateways cannot start).
#
# The live epoch is never touched: the restore gate's G3 compares its rows with the members' trades, and the members'
# positions on these accounts can only go with a fresh epoch (FR-PRG-29), which moves the live rows into an archive this
# script can then clear. The disk snapshots are listed, not deleted here: `rig.sh` keeps only the last epoch's archive
# and deletes the older ones whole, tables and snapshots (FR-PRG-39), so `apply` is left with that one label at most.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
RIG_LIB=1 . "$HERE/rig.sh"
# FIX load clients CLIENT2-8 (prepare.FIX_TEST_ACCOUNTS) and the binary load's own (binary-load.mjs SETS).
TEST_ACCOUNTS="9900002,9900003,9900004,9900005,9900006,9900007,9900008,9900010,9900011,9900012,9900013,9900014,9900015,9900016,9900017"
ACCOUNT_TABLES="orderbook order_view_snapshot trades positions"   # positions last: its foreign key names accounts
ACTION=${1:-}; shift || true
LABELS=(); ACCOUNTS=0; SECRET=0
for a in "$@"; do case $a in
  --dry-run) DRY=1;; --accounts) ACCOUNTS=1;; --secret) SECRET=1;;
  *) epoch_label_ok "$a" || die "want an epoch label [a-z][a-z0-9]{0,15}, got '$a'"; LABELS+=("$a");; esac; done

tables_of(){ # tables_of <prefix>: the account tables this database has under that prefix ('' = the live epoch)
  local t names; names=$(for t in $ACCOUNT_TABLES; do printf "'%s%s'," "$1" "$t"; done)
  sql "select table_name from information_schema.tables where table_schema=database() and table_name in (${names%,})" || true
}
labels(){ sql "select substring(table_name, 5, length(table_name) - 11) from information_schema.tables where table_schema=database() and table_name like 'arc\\_%\\_trades'" || true; }
counts(){ # counts <prefix>: "<table> <rows on the test accounts>" for each account table present
  local t n
  for t in $(tables_of "$1"); do
    n=$(sql "select count(*) from $t where accountid in ($TEST_ACCOUNTS)") && [[ $n =~ ^[0-9]+$ ]] || die "$t cannot be counted"
    echo "$t $n"
  done
}
referenced(){ # every table, live or archived, still holding a test account's row
  local t n
  for t in $(sql "select table_name from information_schema.columns where table_schema=database() and column_name='accountid' and table_name not in ('accounts','accountusers')"); do
    n=$(sql "select count(*) from $t where accountid in ($TEST_ACCOUNTS)") && [[ $n =~ ^[0-9]+$ ]] || die "$t cannot be counted"
    [ "$n" = 0 ] || echo "$t $n"
  done
}

plan(){
  preflight; sql_ready || die "the read model (eod-price-db) cannot be reached"
  step "test accounts: $TEST_ACCOUNTS"
  local l; for l in $(labels); do note "archived epoch $l:"; counts "arc_${l}_" | sed 's/^/    /'; done
  note "live epoch (never touched here; a fresh epoch archives it):"; counts '' | sed 's/^/    /'
  note "directory rows: $(sql "select count(*) from accounts where id in ($TEST_ACCOUNTS)") accounts, $(sql "select count(*) from accountusers where accountid in ($TEST_ACCOUNTS)") accountusers"
  note "Secret fix-test-clients: $(kc get secret fix-test-clients -o name 2>/dev/null || echo absent)"
  note "member-disk snapshots (kept; each is an epoch's archive):"
  gc compute snapshots list --filter='name~^traderx-' --format='value(name,diskSizeGb,creationTimestamp)' | sed 's/^/    /' || true
}

apply(){
  [ ${#LABELS[@]} -ge 1 ] || [ "$ACCOUNTS" = 1 ] || [ "$SECRET" = 1 ] || die "apply: name an archived epoch, --accounts or --secret"
  preflight; sql_ready || die "the read model (eod-price-db) cannot be reached"
  local l t stmts n have
  for l in "${LABELS[@]}"; do
    have=$(tables_of "arc_${l}_"); grep -qx "arc_${l}_trades" <<<"$have" || die "no archived epoch $l (arc_${l}_trades)"
    step "archived epoch $l: the test accounts' rows deleted, one transaction"
    counts "arc_${l}_" > "$EVIDENCE/cleanup-$l-before.txt"; sed 's/^/    /' "$EVIDENCE/cleanup-$l-before.txt"
    stmts=''; for t in $have; do stmts="$stmts delete from $t where accountid in ($TEST_ACCOUNTS);"; done
    if [ "$DRY" = 1 ]; then note "would run: start transaction;$stmts commit"; continue; fi
    sql "start transaction;$stmts commit; select 1" | grep -qx 1 || die "archived epoch $l: the delete failed (rolled back)"
    counts "arc_${l}_" > "$EVIDENCE/cleanup-$l-after.txt"
    n=$(awk '{s+=$2} END{print s+0}' "$EVIDENCE/cleanup-$l-after.txt")
    [ "$n" = 0 ] || die "archived epoch $l: $n test-account rows remain ($EVIDENCE/cleanup-$l-after.txt)"
    note "ok: 0 rows remain"
  done
  if [ "$ACCOUNTS" = 1 ]; then
    step "directory: the test accounts' accounts and accountusers rows"
    have=$(referenced)
    [ -z "$have" ] || die "--accounts: still referenced, clear those first (live rows go with a fresh epoch): $(tr '\n' ';' <<<"$have")"
    if [ "$DRY" = 1 ]; then note "would delete accountusers, then accounts, where the id is a test account"; else
      sql "start transaction; delete from accountusers where accountid in ($TEST_ACCOUNTS); delete from accounts where id in ($TEST_ACCOUNTS); commit; select 1" \
        | grep -qx 1 || die "--accounts: the delete failed (rolled back)"
      note "ok"
    fi
  fi
  if [ "$SECRET" = 1 ]; then
    step "Secret fix-test-clients deleted (render without --fix-test-clients from now on)"
    do_ kubectl --context "$CTX" -n "$NS" delete secret fix-test-clients --ignore-not-found
  fi
}

mkdir -p "$EVIDENCE"
case $ACTION in
  plan) plan;;
  apply) apply;;
  *) echo "usage: $0 plan | apply LABEL... [--accounts] [--secret] [--dry-run]" >&2; exit 64;;
esac
