#!/usr/bin/env bash
# RI-07 isolated startup probe: prove an order reaches the engine AND a trade reaches the read
# model, without touching anyone else's book, reference price or positions.
#
# What the old probe did (scripts/yu15/bring-up-gke.sh section 4, before RI-07): /seed IBM at 200
# for 42422 and 22214, then SELL 1 IBM @ 200 from 42422 into the LIVE book. /seed also sequences a
# PRICE_TICK, so it moved IBM's reference to 200; and the sell matched the best bid >= 200, which on
# 2026-09-18 was a user's resting 100-share IBM buy. Changing the account alone would not have
# helped: a book is per instrument, and price-time priority fills whoever is best, not whoever
# the probe meant.
#
# Isolation here is by INSTRUMENT first, accounts second:
#   * a fresh ticker per run, PRB<unix seconds>, which the catalog, the price feed and the console
#     do not offer. Before any order, every member's /bbo must show NO row for it (never quoted,
#     never traded, nothing resting) and the read model must hold no order or trade on it. A
#     non-empty book is refused, not traded into.
#   * dedicated probe accounts from the reserved range 880000-889999 (default 880001/880002; below
#     the >= 900000 tape-replay range), created through account-service only when absent and
#     labelled as automated.
#   * /seed names ONLY the probe ticker, so no real instrument's reference moves.
#   * a round trip (A sells to B, then B sells to A) leaves both probe accounts flat.
#   * afterwards: exactly 4 trade legs on the ticker, all probe-owned; no open order on it; and a
#     before/after digest of every COMPLETE non-probe order and trade row must be identical. The
#     digest streams every row (SELECT *, primary-key order) through sha256: an earlier version
#     hashed a GROUP_CONCAT, which MariaDB silently truncates at group_concat_max_len.
#   * an existing probe-range account is used only if it is ours (exact automated display name, no
#     user mappings); anything else is refused and never overwritten.
#   * every read-model query must succeed: a failed query is a refusal or a failure, never an
#     empty answer.
#
# Residual window (stated, not hidden): between the empty-book check and the first order, someone
# could type the same never-offered ticker. The post-check detects that (a foreign leg or a changed
# foreign row is a failure); it cannot prevent it.
#
# Usage (gateway reachable at MATCHER_URL, kubectl context CTX):
#   MATCHER_URL=http://localhost:28110 CTX=kind-traderx-ri07-demo bash scripts/ri07/startup-probe.sh
# Exit 0 = proven; 1 = the rig failed the proof; 2 = a precondition refused (nothing was traded).
# Sourcing the file defines the functions without running the probe (scripts/ri07/test-startup-probe-sql.sh).
set -uo pipefail

NS="${NS:-traderx}"
PROBE_A="${PROBE_A:-880001}"
PROBE_B="${PROBE_B:-880002}"
PROBE_PX="${PROBE_PX:-100}"

say() { printf '[probe] %s\n' "$*"; }
refuse() { printf '[probe] [refused] %s\n' "$*" >&2; exit 2; }
fail() { printf '[probe] [FAIL] %s\n' "$*" >&2; exit 1; }

probe_name() { # the display name an automation-owned probe account carries; <A|B>
  printf 'RI-07 startup probe %s (automated, not for trading)' "$1"
}
# One read-model query; stdout = rows (-N -B), exit status = the command's. Never masks a failure.
sql() { "${K[@]}" exec deploy/eod-price-db -c mariadb -- mariadb -utraderx -ptraderx traderx -N -B -e "$1"; }
members() { "${K[@]}" get pods -l app=order-matcher-cluster -o name 2>/dev/null | sed 's|pod/||' | sort; }
bbo_row() { # the ticker's /bbo row on one member, "" if absent; "ERR" if the member did not answer
  local out
  out="$("${K[@]}" exec "$1" -- sh -c 'wget -qO- http://localhost:8080/bbo' 2>/dev/null)" || { echo ERR; return; }
  python3 -c 'import json,sys
d=json.loads(sys.argv[1]); t=sys.argv[2]
print(next((json.dumps(b, sort_keys=True) for b in d["books"] if b.get("ticker")==t), ""))' "${out}" "$2" 2>/dev/null || echo ERR
}
post() { curl -s -m20 -X POST "${MATCHER_URL}$1" -H 'Content-Type: application/json' -d "$2"; }
field() { python3 -c 'import json,sys
try: v=json.loads(sys.argv[1]).get(sys.argv[2], "")
except Exception: v=""
print(v)' "$1" "$2"; }

# sha256 of every COMPLETE order and trade row not owned by the probe, streamed in primary-key order.
# Prints "<sha256> <orders>+<trades> rows"; returns non-zero if either query fails (no partial digest).
foreign_digest() {
  local tmp orders trades
  tmp="$(mktemp)" || return 1
  if ! sql "SELECT * FROM orderbook WHERE accountid NOT IN (${PROBE_A},${PROBE_B}) ORDER BY orderid;" > "${tmp}.o" \
     || ! sql "SELECT * FROM trades WHERE accountid IS NULL OR accountid NOT IN (${PROBE_A},${PROBE_B}) ORDER BY id;" > "${tmp}.t"; then
    rm -f "${tmp}" "${tmp}.o" "${tmp}.t"; return 1
  fi
  orders="$(wc -l < "${tmp}.o" | tr -d ' ')"; trades="$(wc -l < "${tmp}.t" | tr -d ' ')"
  printf '%s %s+%s rows\n' "$( { cat "${tmp}.o"; echo '#trades'; cat "${tmp}.t"; } | shasum -a 256 | cut -d' ' -f1)" "${orders}" "${trades}"
  rm -f "${tmp}" "${tmp}.o" "${tmp}.t"
}

# absent | ours | conflict:<why>; returns non-zero if the read model could not answer.
probe_account_state() { # <id> <A|B>
  local rows name users
  rows="$(sql "SELECT displayname FROM accounts WHERE id=$1;")" || return 1
  [[ -z "${rows}" ]] && { echo absent; return 0; }
  name="${rows}"
  users="$(sql "SELECT COUNT(*) FROM accountusers WHERE accountid=$1;")" || return 1
  if [[ "${name}" != "$(probe_name "$2")" ]]; then echo "conflict:display name '${name}' is not the automated probe name"
  elif [[ "${users}" != 0 ]]; then echo "conflict:${users} user mapping(s) -- a person can trade this account"
  else echo ours; fi
}

main() {
  MATCHER_URL="${MATCHER_URL:?set MATCHER_URL to the cluster gateway, e.g. http://localhost:28110}"
  CTX="${CTX:?set CTX to the kube context of the rig}"
  TICKER="${PROBE_TICKER:-PRB$(date -u +%s)}"
  K=(kubectl --context "${CTX}" -n "${NS}")

  [[ "${TICKER}" =~ ^PRB[0-9]+$ ]] || refuse "probe ticker must match ^PRB[0-9]+\$ (got '${TICKER}'); real instruments are never probed"
  # 880000-889999 is reserved for automated probes: below the >= 900000 tape-replay range (which
  # the members attribute as replayed flow) and disjoint from every directory account a person trades.
  local a
  for a in "${PROBE_A}" "${PROBE_B}"; do
    [[ "${a}" =~ ^88[0-9]{4}$ ]] || refuse "probe account ${a} is outside the reserved probe range 880000-889999"
  done
  [[ "${PROBE_A}" != "${PROBE_B}" ]] || refuse "probe accounts must differ (self-trade prevention would cancel the cross)"

  # ---- preconditions (exit 2: nothing traded) -----------------------------------------------
  local code n row m
  code="$(curl -s -m8 -o /dev/null -w '%{http_code}' "${MATCHER_URL}/ready")"
  [[ "${code}" == 200 ]] || refuse "gateway ${MATCHER_URL}/ready answered HTTP ${code:-none}"
  MEMBERS=($(members))
  [[ ${#MEMBERS[@]} -ge 1 ]] || refuse "no order-matcher-cluster pods found on ${CTX}"
  n="$(sql 'SELECT 1;')" && [[ "${n}" == 1 ]] || refuse "eod-price-db (read model) not reachable on ${CTX}"

  say "ticker ${TICKER}, accounts ${PROBE_A}/${PROBE_B}, price ${PROBE_PX}"
  for m in "${MEMBERS[@]}"; do
    row="$(bbo_row "${m}" "${TICKER}")"
    [[ "${row}" == ERR ]] && refuse "member ${m} did not answer /bbo"
    [[ -z "${row}" ]] || refuse "book for ${TICKER} is not empty on ${m}: ${row}"
  done
  n="$(sql "SELECT (SELECT COUNT(*) FROM orderbook WHERE security='${TICKER}')+(SELECT COUNT(*) FROM trades WHERE security='${TICKER}');")" \
    || refuse "read-model query failed while checking ${TICKER}"
  [[ "${n}" == 0 ]] || refuse "read model already holds ${n} order/trade row(s) on ${TICKER}"
  say "empty-book check passed on ${#MEMBERS[@]} member(s) and the read model"

  # Probe accounts: create through the owning service only when ABSENT; use an existing one only when
  # it is provably ours. A numeric range is not ownership; a conflicting record is never overwritten.
  local pair id state out
  for pair in "${PROBE_A}:A" "${PROBE_B}:B"; do
    id="${pair%%:*}"
    state="$(probe_account_state "${id}" "${pair#*:}")" || refuse "read-model query failed while checking probe account ${id}"
    case "${state}" in
      ours) ;;
      absent)
        out="$("${K[@]}" exec deploy/edge-proxy -- curl -s -m15 -X POST http://account-service:18088/account/ \
          -H 'Content-Type: application/json' -d "{\"id\":${id},\"displayName\":\"$(probe_name "${pair#*:}")\"}" 2>/dev/null)"
        [[ "$(field "${out}" id)" == "${id}" ]] || refuse "account-service did not create probe account ${id}: ${out:-no answer}"
        state="$(probe_account_state "${id}" "${pair#*:}")" || refuse "read-model query failed re-checking probe account ${id}"
        [[ "${state}" == ours ]] || refuse "probe account ${id} after create: ${state}" ;;
      *) refuse "account ${id} exists and is not the automated probe's (${state#conflict:}); not overwriting it. Choose other PROBE_A/PROBE_B in 880000-889999" ;;
    esac
  done

  local BEFORE AFTER TRADES_BEFORE
  BEFORE="$(foreign_digest)" || refuse "could not digest the non-probe rows (read-model query failed)"
  TRADES_BEFORE="$(sql 'SELECT COUNT(*) FROM trades;')" || refuse "read-model query failed counting trades"

  for a in "${PROBE_A}" "${PROBE_B}"; do
    out="$(post /seed "{\"accountId\":${a},\"tickers\":\"${TICKER}\",\"price\":${PROBE_PX}}")"
    [[ "${out}" == *'"seeded":true'* ]] || refuse "/seed for ${a} on ${TICKER} answered: ${out:-nothing}"
  done

  # ---- the round trip ----------------------------------------------------------------------
  # UNTYPED limit orders on purpose: every tier since YU12 accepts them (a pre-YU18 rig has no typed
  # orders at all), and a probe must not depend on the feature it is often run to vet. Each crossing
  # leg exactly consumes the single resting unit, so nothing is left behind; the effect-end checks
  # below would catch it if something were.
  order() { # <account> <side>; prints the ack; fails unless ACCEPTED (kind 1)
    local o
    o="$(post /orders "{\"accountId\":$1,\"ticker\":\"${TICKER}\",\"side\":\"$2\",\"quantity\":1,\"limitPrice\":${PROBE_PX}}")"
    [[ "${o}" == *'"kind":1'* ]] || fail "order $1 $2 1 ${TICKER} @ ${PROBE_PX} not accepted: ${o:-no answer}"
    printf '%s' "${o}"
  }
  local leg1 leg2 leg3 leg4 i
  leg1="$(order "${PROBE_A}" Sell)" || exit 1
  # The resting sell must be the ONLY thing on the book: ask == our price, no bid.
  row="$(bbo_row "${MEMBERS[0]}" "${TICKER}")"
  for i in 1 2 3 4 5 6 7 8 9 10; do [[ "${row}" == *'"ask"'* ]] && break; sleep 1; row="$(bbo_row "${MEMBERS[0]}" "${TICKER}")"; done
  [[ "${row}" == *'"ask"'* && "${row}" != *'"bid"'* ]] || fail "after the resting sell, ${MEMBERS[0]} /bbo shows ${row:-nothing}"
  leg2="$(order "${PROBE_B}" Buy)" || exit 1
  leg3="$(order "${PROBE_B}" Sell)" || exit 1
  leg4="$(order "${PROBE_A}" Buy)" || exit 1
  say "4 legs accepted: refs $(field "${leg1}" orderRef) $(field "${leg2}" orderRef) $(field "${leg3}" orderRef) $(field "${leg4}" orderRef)"

  # ---- effect end: every read must succeed; a failed read is a failure, not an empty answer ----
  q() { sql "$1" || fail "read-model query failed after trading: $1"; }
  local legs foreign_legs open flat
  for i in $(seq 1 30); do
    legs="$(q "SELECT COUNT(*) FROM trades WHERE security='${TICKER}';")" || exit 1
    [[ "${legs}" == 4 ]] && break
    sleep 2
  done
  [[ "${legs}" == 4 ]] || fail "read model shows ${legs:-?} trade leg(s) on ${TICKER}, expected 4 (engine accepted all four orders). THE READ MODEL IS NOT LIVE -- do not demo from this rig."
  foreign_legs="$(q "SELECT COUNT(*) FROM trades WHERE security='${TICKER}' AND (accountid IS NULL OR accountid NOT IN (${PROBE_A},${PROBE_B}));")" || exit 1
  [[ "${foreign_legs}" == 0 ]] || fail "${foreign_legs} trade leg(s) on ${TICKER} belong to a non-probe account"
  open="$(q "SELECT COUNT(*) FROM orderbook WHERE security='${TICKER}' AND status IN ('NEW','PARTIALLY_FILLED','PENDING_TRIGGER','SUSPENDED','QUEUED');")" || exit 1
  [[ "${open}" == 0 ]] || fail "${open} probe order(s) still open on ${TICKER}"
  for i in 1 2 3 4 5 6 7 8 9 10; do
    flat="$(q "SELECT COALESCE(SUM(ABS(quantity)),0) FROM positions WHERE security='${TICKER}';")" || exit 1
    [[ "${flat}" == 0 ]] && break; sleep 2
  done
  [[ "${flat}" == 0 ]] || fail "probe accounts are not flat on ${TICKER} (sum |qty| = ${flat:-?})"
  AFTER="$(foreign_digest)" || fail "could not digest the non-probe rows after trading (read-model query failed)"
  [[ "${AFTER}" == "${BEFORE}" ]] || fail "a NON-probe order or trade row changed during the probe (before ${BEFORE}; after ${AFTER})"
  say "verified: trades $(q 'SELECT COUNT(*) FROM trades;') (was ${TRADES_BEFORE}), 4 probe legs on ${TICKER}, 0 foreign legs, 0 open, flat, non-probe rows unchanged (${AFTER#* })"
  printf '{"probe":"ri07-startup","ticker":"%s","accounts":[%s,%s],"price":%s,"legs":4,"foreignLegs":0,"foreignDigest":"%s","foreignRows":"%s","result":"PASS"}\n' \
    "${TICKER}" "${PROBE_A}" "${PROBE_B}" "${PROBE_PX}" "${AFTER%% *}" "${AFTER#* }"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then main "$@"; fi
