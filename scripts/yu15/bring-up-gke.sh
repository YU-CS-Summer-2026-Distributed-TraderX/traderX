#!/usr/bin/env bash
# bring-up-gke.sh — scale the GKE rig back up from zero and hand back a rig that actually works.
#
# WHY THIS EXISTS. Members run on an emptyDir (statefulset-emptydir.yaml is what this tier deploys),
# so scaling nodes to zero destroys their state and they return with trade counter 0 — while
# eod-price-db has a PVC and keeps yesterday's rows. Trade ids are <tradeSeq>-<side> and carry NO
# epoch, so every trade the fresh engine books mints an id that already exists and trade-processor
# drops it as "Duplicate trade delivery ignored".
#
# The failure is silent and total: orders are accepted, the engine books them, all three members
# agree, and NOTHING reaches the blotter, positions or the read model. Every pod is Running. It cost
# a morning on 2026-08-20 before anyone asked why the blotter was empty.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CTX="${CTX:-gke_traderx-505400_us-east1-b_traderx-bench}"
NS="${NS:-traderx}"
ZONE="${ZONE:-us-east1-b}"
CLUSTER="${CLUSTER:-traderx-bench}"
K=(kubectl --context "${CTX}" -n "${NS}")
SKIP_RESIZE="${SKIP_RESIZE:-0}"

say() { echo "[bring-up] $*"; }
die() { echo "[fail] $*" >&2; exit 1; }

# ---- 1. nodes -------------------------------------------------------------------------------
# support-pool FIRST and deliberately: it is the only untainted pool, so kube-system (CoreDNS,
# konnectivity) has nowhere to run until it exists. Bring the tainted pools up into a cluster whose
# DNS is still pending and the members crashloop on name resolution, which reads as a cluster fault.
if [[ "${SKIP_RESIZE}" != "1" ]]; then
  for spec in support-pool:1 blp-c4d-tuned-pool:3 default-pool:3; do
    pool="${spec%%:*}"; n="${spec##*:}"
    say "scaling ${pool} -> ${n}"
    gcloud container clusters resize "${CLUSTER}" --node-pool "${pool}" --num-nodes "${n}" \
      --zone "${ZONE}" --quiet >/dev/null 2>&1 || die "resize of ${pool} failed"
  done
fi
say "waiting for nodes"
for _ in $(seq 1 60); do
  ready="$("${K[@]}" get nodes --no-headers 2>/dev/null | grep -c ' Ready ' || true)"
  [[ "${ready}" -ge 7 ]] && break
  sleep 10
done

# ---- 2. members + a leader ------------------------------------------------------------------
say "waiting for three members and an elected leader"
leader=""
for _ in $(seq 1 60); do
  leader=""
  for m in 0 1 2; do
    role="$("${K[@]}" exec "order-matcher-cluster-${m}" -- wget -qO- localhost:8080/health 2>/dev/null \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["role"])' 2>/dev/null || true)"
    [[ "${role}" == "LEADER" ]] && leader="${m}"
  done
  [[ -n "${leader}" ]] && break
  sleep 10
done
[[ -n "${leader}" ]] || die "no leader after 10 minutes — check member logs before going further"
say "leader is member ${leader}"

# ---- 3. THE WEDGE CHECK ----------------------------------------------------------------------
# Compare the engine's own trade counter against the highest trade id already in SQL. This is the
# same check scripts/yu15/run-proofs.sh uses, for the same reason: it detects the STATE rather than
# the cause, so it fires whatever produced the mismatch — a scale-to-zero, a PVC wipe, a hand-rolled
# StatefulSet. Both numbers count PER SIDE, so they are directly comparable.
sql() { "${K[@]}" exec deploy/eod-price-db -- mariadb -utraderx -ptraderx traderx -N -e "$1" 2>/dev/null; }
ENG="$("${K[@]}" exec "order-matcher-cluster-${leader}" -- sh -c 'wget -qO- http://localhost:8080/metrics' 2>/dev/null \
  | awk '/^traderx_cluster_trades/ {print $2}')"
SQLMAX="$(sql "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(id,'-',1) AS UNSIGNED)),0) FROM trades;")"
[[ "${ENG}" =~ ^[0-9]+$ && "${SQLMAX}" =~ ^[0-9]+$ ]] \
  || die "could not read engine counter ('${ENG}') or SQL max trade id ('${SQLMAX}') — refusing to run blind"
say "engine trade counter=${ENG}  highest trade id in SQL=${SQLMAX}"

if [[ "${ENG}" -lt "${SQLMAX}" ]]; then
  say "WEDGED: the projection holds a dead epoch's ids that this engine will re-mint. Clearing it."
  # A bare SQL clear is the right heal ONLY because we are here at bring-up, before this epoch has
  # booked anything. Mid-life the projection may already have MISSED trades the engine booked, and
  # then only a full fresh-epoch rebuild makes "SQL is a projection of the log" true again.
  sql "DELETE FROM trades; DELETE FROM positions; DELETE FROM orderbook;"
  "${K[@]}" rollout restart deploy/trade-processor deploy/position-service >/dev/null
  "${K[@]}" rollout status deploy/trade-processor --timeout=300s >/dev/null
  "${K[@]}" rollout status deploy/position-service --timeout=300s >/dev/null
  say "projection cleared and consumers restarted"
elif [[ "${ENG}" -gt "${SQLMAX}" ]]; then
  say "engine is AHEAD of SQL — ordinary bridge lag, not a wedge. Leaving the projection alone."
else
  say "engine and SQL agree — not wedged"
fi

# The gateway's public address, needed by both the admission step and the proof below.
GW="$("${K[@]}" get svc order-matcher-gw -o jsonpath='{.status.loadBalancer.ingress[0].ip}')"
[[ -n "${GW}" ]] || die "gateway LoadBalancer has no external IP yet"

# ---- 3b. ADMIT EVERY DIRECTORY ACCOUNT --------------------------------------------------------
# The account directory and the engine's risk state are TWO different things, and a fresh epoch
# resets only the second. account-service keeps the directory (which is what every account dropdown
# in both UIs lists), while the engine holds its own admitted set and answers UNKNOWN_ACCOUNT to
# anything not in it.
#
# So after any epoch roll the UI offers eight accounts of which two work — the two the fixtures
# happen to seed. Picking any other one produces a run where every single order is rejected
# UNKNOWN_ACCOUNT, which reads as a broken account rather than an unadmitted one. Observed
# 2026-08-20 in a live trading session with four actors: 22214 and 42422 accepted, 17017 and 10031
# rejected 13 of 13.
#
# Idempotent, so re-running costs nothing. Same class as the option chain that a fresh epoch drops.
say "admitting every directory account to the engine's risk state"
# Read the directory from account-service itself, IN-CLUSTER. It is not behind the gateway — the
# gateway serves the engine, account-service serves the directory, and conflating them is how this
# step reads an empty list and silently admits nothing.
ACCTS=( $("${K[@]}" exec deploy/edge-proxy -- sh -c 'curl -s -m15 http://account-service:18088/account/' 2>/dev/null \
  | python3 -c "import sys,json;[print(a['id']) for a in json.load(sys.stdin)]" 2>/dev/null || true) )
if [[ "${#ACCTS[@]}" -eq 0 ]]; then
  say "  could not read the account directory — skipping admission (accounts may reject UNKNOWN_ACCOUNT)"
else
  for a in "${ACCTS[@]}"; do
    curl -sf -m 20 -X POST "http://${GW}:18110/risk/control/account" \
      -H 'Content-Type: application/json' \
      -H "X-Risk-Control-Token: ${RISK_CONTROL_TOKEN:-dev-risk-control}" \
      -H 'X-Risk-Operator: bring-up' \
      -d "{\"accountId\":${a},\"enabled\":true}" >/dev/null 2>&1 || say "  admission failed for ${a}"
  done
  say "  admitted ${#ACCTS[@]} accounts"
fi

# ---- 3c. ADMIT EVERY CATALOG INSTRUMENT -------------------------------------------------------
# Exactly the 3b failure one noun over, and it hides better. The engine gates on
# `securityEnabled[securityId]` and answers UNKNOWN_SECURITY to anything unset; `resolveSecurityId`
# auto-registers a ticker on first sight, so the symbol gets an ID and still cannot trade. Nothing
# in the bring-up ever admitted a security, so the ONLY tradable instrument was whichever one the
# fixtures happened to enable — IBM.
#
# Observed 2026-08-20: a session over 12 instruments and 4 accounts, 16 orders, 0 accepted. Every
# equity came back UNKNOWN_SECURITY, and the console's own band panel read "never accepted" for all
# eleven non-IBM names, which is the panel being right about a rig that was wrong.
#
# Admission is ENOUGH — verified, no price seed required. A seed would also set the mark, and under
# ADR-051 that only applies while no trade has printed, so seeding here would be an inert write on a
# used book and a silent mark change on a fresh one. Enable, and let the session set its own prices.
#
# The catalog is read from the LIVE price-publisher rather than copied here: `PRICE_TICKERS` is
# already duplicated across eod-chain.yaml, price-publisher and reference-data manifests, and a
# fourth copy in this script would drift from all three.
say "admitting every catalog instrument to the engine's risk state"
TICKERS="$("${K[@]}" get deploy price-publisher \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PRICE_TICKERS")].value}' 2>/dev/null || true)"
SEC_N="$(printf '%s' "${TICKERS}" | tr ',' '\n' | grep -c . || true)"
# Shape-test, not emptiness-test: a partial read is worse than none, because it admits a few and
# reports success. The catalog is 44; anything under 20 means the read went wrong, not that the
# catalog shrank.
if [[ "${SEC_N:-0}" -lt 20 ]]; then
  say "  catalog read returned ${SEC_N:-0} tickers, expected >=20 — SKIPPING (instruments will reject UNKNOWN_SECURITY)"
else
  sec_ok=0
  for t in $(printf '%s' "${TICKERS}" | tr ',' ' '); do
    if curl -sf -m 20 -X POST "http://${GW}:18110/risk/control/security" \
      -H 'Content-Type: application/json' \
      -H "X-Risk-Control-Token: ${RISK_CONTROL_TOKEN:-dev-risk-control}" \
      -H 'X-Risk-Operator: bring-up' \
      -d "{\"ticker\":\"${t}\",\"enabled\":true}" >/dev/null 2>&1; then
      sec_ok=$((sec_ok + 1))
    else
      say "  admission failed for ${t}"
    fi
  done
  say "  admitted ${sec_ok}/${SEC_N} instruments"
  [[ "${sec_ok}" -eq "${SEC_N}" ]] || say "  WARNING: $((SEC_N - sec_ok)) instrument(s) will reject UNKNOWN_SECURITY"
fi

# ---- 4. PROVE IT, rather than report it -------------------------------------------------------
# The check above can only say the ids no longer collide. Whether a trade actually reaches the read
# model is a different claim, and it is the one that matters — so book a real cross and look for it.
say "proving the path end to end"
BEFORE="$(sql 'SELECT COUNT(*) FROM trades;')"
for acct in 22214 42422; do
  curl -sf -m 20 -X POST "http://${GW}:18110/seed" -H 'Content-Type: application/json' \
    -d "{\"accountId\":${acct},\"tickers\":\"IBM\",\"price\":200.00}" >/dev/null || die "seed failed for ${acct}"
done
curl -sf -m 20 -X POST "http://${GW}:18110/orders" -H 'Content-Type: application/json' \
  -d '{"accountId":42422,"ticker":"IBM","side":"Sell","quantity":1,"limitPrice":200.00}' >/dev/null
curl -sf -m 20 -X POST "http://${GW}:18110/orders" -H 'Content-Type: application/json' \
  -d '{"accountId":22214,"ticker":"IBM","side":"Buy","quantity":1,"limitPrice":200.00}' >/dev/null
for _ in $(seq 1 20); do
  AFTER="$(sql 'SELECT COUNT(*) FROM trades;')"
  [[ "${AFTER:-0}" -gt "${BEFORE:-0}" ]] && break
  sleep 3
done
[[ "${AFTER:-0}" -gt "${BEFORE:-0}" ]] \
  || die "a cross booked in the engine never reached the projection (${BEFORE} -> ${AFTER}). THIS IS THE WEDGE. Do not demo from this rig."
say "verified: trades ${BEFORE} -> ${AFTER}, the read model is live"
say "rig is up. https://yaakovseif.dev"
