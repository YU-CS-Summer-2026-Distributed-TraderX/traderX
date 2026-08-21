#!/usr/bin/env bash
# seed-option-chain.sh — YU14: seed the packaged listed-equity-option chain into the running
# cluster gateway and smoke-prove one option cross books.
#
# The engine silently rejects orders whose security is not enabled or has no price tick, and
# those rejects surface nowhere on some paths — so this script is the state's first acceptance
# step: it seeds (register + enable + price) every contract through the sequenced /seed path,
# then proves the book actually crosses one contract by watching the gateway fill counter.
#
# ENABLEMENT NO LONGER DEPENDS ON THIS SCRIPT BEING RUN BY HAND. scripts/yu15/seed-proof-fixtures.sh
# now seeds the whole chain at live premiums on every fresh epoch, which is what closed
# issues/resolved/an-epoch-roll-silently-drops-instrument-classes.md -- this script was referenced
# by nothing but the README, so before that the option class was tradeable only on the days somebody
# remembered to run it. What is still only here is the SMOKE: the proof that a contract actually
# CROSSES. Keep that; do not re-add a second enablement path that can drift from the seeder's.
#
# Usage: MATCHER_URL=http://localhost:18110 ./seed-option-chain.sh
set -euo pipefail

MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
ACCOUNT_A="${ACCOUNT_A:-42422}"   # resting side
ACCOUNT_B="${ACCOUNT_B:-22214}"   # aggressor side (distinct account, both sides real)

# Chain: 2 underlyings x 2 expiries x 3 strikes x call/put (matches reference-data/instruments.csv)
UNDERLYINGS=(AAPL MSFT)
SPOTS=(241.80 388.50)
EXPIRIES=(260918 261218)
AAPL_STRIKES=(220 240 260)
MSFT_STRIKES=(370 390 410)

seed() { # seed <accountId> <tickers-csv> <price>
  local out
  out="$(curl -sf --max-time 20 -X POST "${MATCHER_URL}/seed" \
    -H 'Content-Type: application/json' \
    -d "{\"accountId\":${1},\"tickers\":\"${2}\",\"price\":${3}}")"
  [[ "${out}" == *'"seeded":true'* ]] || { echo "[seed] FAILED for ${2}: ${out}" >&2; exit 1; }
}

metric() { # metric <name-with-labels>
  curl -sf --max-time 10 "${MATCHER_URL}/metrics" | awk -v m="${1}" '$0 ~ m {print $2; exit}'
}

order() { # order <accountId> <ticker> <side> <qty> <limitPrice> -> HTTP code + body
  curl -s --max-time 20 -o /tmp/yu14-order-body -w '%{http_code}' -X POST "${MATCHER_URL}/orders" \
    -H 'Content-Type: application/json' \
    -d "{\"accountId\":${1},\"ticker\":\"${2}\",\"side\":\"${3}\",\"quantity\":${4},\"limitPrice\":${5}}"
}

# Premium: intrinsic + $2.00 time value, floored at $0.50 — plausible, on-grid, per contract.
premium() { # premium <spot> <strike> <C|P>
  awk -v s="${1}" -v k="${2}" -v cp="${3}" 'BEGIN {
    i = (cp == "C") ? s - k : k - s; if (i < 0) i = 0;
    p = i + 2.0; if (p < 0.5) p = 0.5; printf "%.2f", p }'
}

echo "[chain] gateway ${MATCHER_URL} — waiting ready"
for i in $(seq 1 30); do
  curl -sf --max-time 5 "${MATCHER_URL}/ready" >/dev/null && break
  [[ "${i}" == 30 ]] && { echo "[chain] gateway never became ready" >&2; exit 1; }
  sleep 2
done

echo "[chain] seeding underlyings at spot"
seed "${ACCOUNT_A}" "AAPL" "241.80"
seed "${ACCOUNT_B}" "MSFT" "388.50"

echo "[chain] seeding the option chain (24 contracts)"
count=0
for u in 0 1; do
  root="${UNDERLYINGS[$u]}"; spot="${SPOTS[$u]}"
  strikes_name="${root}_STRIKES[@]"
  for expiry in "${EXPIRIES[@]}"; do
    for strike in "${!strikes_name}"; do
      for cp in C P; do
        ticker="$(printf '%s%s%s%08d' "${root}" "${expiry}" "${cp}" "$((strike * 1000))")"
        px="$(premium "${spot}" "${strike}" "${cp}")"
        seed "${ACCOUNT_A}" "${ticker}" "${px}"
        count=$((count + 1))
      done
    done
  done
done
echo "[chain] seeded ${count} contracts"

# ----- smoke: one option cross must BOOK (the silent-reject gate, exercised) ----------------
SMOKE="AAPL260918C00240000"
PX="$(premium 241.80 240 C)"   # ~$3.80
fills_before="$(metric 'traderx_order_events_total.event="fill"')"

code="$(order "${ACCOUNT_A}" "${SMOKE}" Sell 5 "${PX}")"
[[ "${code}" == 200 ]] || { echo "[smoke] resting sell HTTP ${code}: $(cat /tmp/yu14-order-body)" >&2; exit 1; }
code="$(order "${ACCOUNT_B}" "${SMOKE}" Buy 5 "${PX}")"
[[ "${code}" == 200 ]] || { echo "[smoke] crossing buy HTTP ${code}: $(cat /tmp/yu14-order-body)" >&2; exit 1; }

sleep 1
fills_after="$(metric 'traderx_order_events_total.event="fill"')"
if [[ "${fills_after}" -gt "${fills_before}" ]]; then
  echo "[smoke] PASS — option cross booked on ${SMOKE} @ \$${PX} (fills ${fills_before} -> ${fills_after})"
else
  echo "[smoke] FAIL — orders accepted but nothing filled (fills ${fills_before} -> ${fills_after})." >&2
  echo "[smoke] Classic silent-reject signature: check security enablement / price tick." >&2
  exit 1
fi
