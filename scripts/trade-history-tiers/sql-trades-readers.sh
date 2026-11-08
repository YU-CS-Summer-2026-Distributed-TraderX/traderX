#!/usr/bin/env bash
# FR-THT-05: every file that reads or writes the SQL `trades` table, against the recorded inventory.
#   sql-trades-readers.sh              the cut gate: lists what still touches SQL trades beyond its exception rows and
#                                      schema (lines not marked KEEP: or HISTORICAL:); exit 1 while anything does
#   sql-trades-readers.sh --inventory  exit 0 only when the tree matches sql-trades-readers.txt exactly: a new reader,
#                                      or a moved one still listed, fails (CI-safe while the move is under way)
#   sql-trades-readers.sh --selftest   both modes on a planted tree, both ways
# Scope: the YU18 state's operative sources, scripts and the console. ClickHouse (history-feed, the bake-off) and q
# (anything named *kdb*) also say `from trades` and are not SQL, nor is a line calling `hist` (the proofs' ClickHouse
# reader, ADR-102 cutover); Markdown is prose.
set -euo pipefail
ROOT=${THT_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}
LIST=$ROOT/scripts/trade-history-tiers/sql-trades-readers.txt
# Tracked files only: the inventory describes the committed tree, not a peer's work in progress.
found(){ git -C "$ROOT" ls-files -z -- specs/YU18-risk-integration scripts web-front-end-console \
  | grep -zv -e '/history-feed/' -e '/history-store-bakeoff/' -e 'kdb' -e '/node_modules/' -e '^scripts/trade-history-tiers/' -e '\.md$' \
  | (cd "$ROOT" && xargs -0 rg -l -i -P -e '^(?!.*\bhist\b).*\b(from|join|into|update|table)\s+`?trades\b' 2>/dev/null || true) | sort; }
recorded(){ sed -e 's/[[:space:]]*#.*//' -e '/^[[:space:]]*$/d' "$LIST" | sort; }
# FR-THT-01 (cutover 2026-11-08): readers and writers of the exception rows (Rejected, forced Settled) and the schema
# stay on SQL (KEEP:); dated one-off evidence scripts bound to a deleted cluster are not rerun (HISTORICAL:).
kept(){ grep -E '#[[:space:]]*(KEEP|HISTORICAL):' "$LIST" | sed 's/[[:space:]]*#.*//' | sort; }

case ${1:-} in
  --inventory)
    new=$(comm -23 <(found) <(recorded)); gone=$(comm -13 <(found) <(recorded))
    [ -z "$new" ] || printf 'NEW SQL trades reader (move it to the history store, or record it):\n%s\n' "$new"
    [ -z "$gone" ] || printf 'no longer touches SQL trades (remove from %s):\n%s\n' "${LIST#$ROOT/}" "$gone"
    [ -z "$new$gone" ] && echo "inventory ok: $(recorded | wc -l | tr -d ' ') recorded readers" ;;
  --selftest)
    t=$(mktemp -d); trap 'rm -rf "$t"' EXIT; git init -q "$t"; mkdir -p "$t/scripts/trade-history-tiers" "$t/scripts/x" "$t/specs/YU18-risk-integration" "$t/web-front-end-console"
    : > "$t/scripts/trade-history-tiers/sql-trades-readers.txt"
    echo 'select q from trades' > "$t/web-front-end-console/kdb-query.mjs"      # q, not SQL
    echo 'SELECT count(*) FROM trades' > "$t/scripts/x/recon.sh"
    git -C "$t" add -A; ok=1; me=$0
    THT_ROOT=$t "$me" --inventory >/dev/null 2>&1 && { echo "FAIL: an unrecorded reader passed --inventory"; ok=0; }
    echo 'scripts/x/recon.sh   # recon' > "$t/scripts/trade-history-tiers/sql-trades-readers.txt"
    THT_ROOT=$t "$me" --inventory >/dev/null 2>&1 || { echo "FAIL: the recorded reader failed --inventory"; ok=0; }
    THT_ROOT=$t "$me" >/dev/null 2>&1 && { echo "FAIL: the drop gate passed with a reader left"; ok=0; }
    echo 'SELECT * FROM trades WHERE accountid=?' > "$t/scripts/x/blotter.sh"; git -C "$t" add -A
    printf 'scripts/x/recon.sh   # recon\nscripts/x/blotter.sh # KEEP: window\n' > "$t/scripts/trade-history-tiers/sql-trades-readers.txt"
    THT_ROOT=$t "$me" 2>/dev/null | grep -qx scripts/x/blotter.sh && { echo "FAIL: the gate counted a KEEP reader"; ok=0; }
    THT_ROOT=$t "$me" >/dev/null 2>&1 && { echo "FAIL: the gate passed with a non-KEEP reader beside a KEEP one"; ok=0; }
    echo 'curl history-feed:18095/history/accounts/1/fills' > "$t/scripts/x/recon.sh"; git -C "$t" add -A   # moved
    echo 'SELECT 1 FROM trades' > "$t/scripts/x/untracked.sh"                 # a peer's untracked file: not counted
    THT_ROOT=$t "$me" >/dev/null 2>&1 || { echo "FAIL: the gate refused a tree with only a KEEP reader"; ok=0; }
    THT_ROOT=$t "$me" --inventory >/dev/null 2>&1 && { echo "FAIL: a stale inventory entry passed"; ok=0; }
    [ $ok = 1 ] && echo "selftest PASS" ;;
  "")
    left=$(found)
    left=$(comm -23 <(printf '%s\n' "$left" | sed '/^$/d') <(kept))
    [ -z "$left" ] && { echo "only blotter-window readers left: rows mode may stop being written (FR-THT-01)"; exit 0; }
    printf '%s\n' "$left"; echo "$(wc -l <<<"$left" | tr -d ' ') files still read SQL trades as history" >&2; exit 1 ;;
  *) echo "usage: $0 [--inventory|--selftest]" >&2; exit 2 ;;
esac
