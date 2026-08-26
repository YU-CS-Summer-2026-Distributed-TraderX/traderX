#!/usr/bin/env bash
# ADR-070 demo lever: jump the replay clock to any point on the tape.
#
#   bash scripts/yu17/replay-jump.sh 2025-03-11          # that day's open (09:30 ET)
#   bash scripts/yu17/replay-jump.sh 2025-03-11 14:30    # mid-afternoon of the selloff
#   bash scripts/yu17/replay-jump.sh end                 # past Mar 31: the HOLD, asOf ageing
#
# Mechanism: the clock is stateless — position = (now - epochStartMs) x compression — so moving
# the replay-epoch stamp moves the tape, exactly as yu17-taq-replay's hold arm does. This
# deliberately DE-ANCHORS the clock from the real mint: it is a demo lever, not an operational
# stamp. RESTORE when done (or before any proof runs — yu17-taq-replay re-derives position from
# the stamp and will happily agree with a jumped clock, but the next fresh epoch expects its own):
#
#   bash -c 'source scripts/yu15/lib-replay-epoch.sh; K="kubectl --context kind-traderx-yu12-cluster -n traderx"; stamp_replay_epoch'
#
# Reads the day list and compression from the Secret the publisher actually mounts, so the jump
# always agrees with the tape being replayed.
set -euo pipefail

CTX="${CTX:-kind-traderx-yu12-cluster}"
NS="${NS:-traderx}"
K=(kubectl --context "${CTX}" -n "${NS}")

TARGET="${1:?usage: replay-jump.sh <YYYY-MM-DD|end> [HH:MM (ET, 09:30-16:00)]}"
TOD="${2:-09:30}"

EXTRACT_FILE="$(mktemp)"
trap 'rm -f "${EXTRACT_FILE}"' EXIT
"${K[@]}" get secret taq-replay-extract -o 'jsonpath={.data.extract\.json\.gz}' \
  | base64 -d | gunzip > "${EXTRACT_FILE}"
[[ -s "${EXTRACT_FILE}" ]] || { echo "[fail] no taq-replay-extract Secret on the rig"; exit 1; }

MS="$(python3 - "$TARGET" "$TOD" "${EXTRACT_FILE}" <<'EOF'
import json, sys, time
ex = json.load(open(sys.argv[3]))
days = [d['date'] for d in ex['days']]
target, tod = sys.argv[1], sys.argv[2]
if target == 'end':
    tape = len(days) * ex['sessionSeconds'] + ex['windowSeconds']  # just past the last window
else:
    if target not in days:
        sys.exit(f"{target} is not a tape day; range {days[0]}..{days[-1]} (weekends/holidays absent)")
    h, m = map(int, tod.split(':'))
    into = max(0, min(ex['sessionSeconds'] - 1, (h * 3600 + m * 60) - (9 * 3600 + 30 * 60)))
    tape = days.index(target) * ex['sessionSeconds'] + into
print(int(time.time() * 1000 - tape / ex['compression'] * 1000))
EOF
)"

"${K[@]}" create configmap replay-epoch --from-literal=epochStartMs="${MS}" \
  --dry-run=client -o yaml | "${K[@]}" apply -f - >/dev/null
"${K[@]}" rollout restart deploy/price-publisher >/dev/null
"${K[@]}" rollout status deploy/price-publisher --timeout=180s >/dev/null
sleep 5
"${K[@]}" exec deploy/price-publisher -- wget -qO- localhost:18100/health 2>/dev/null \
  | python3 -c 'import json,sys; p=json.load(sys.stdin)["taqReplay"]["position"]; print("[ok] tape now at", p["tapeDate"], "(day", p["dayIndex"], "window", p["windowIndex"], "asOf", p["asOf"], "held", p["held"], ")")'
echo "[note] the clock is DE-ANCHORED from the mint for this demo; restore with:"
echo "       bash -c 'source scripts/yu15/lib-replay-epoch.sh; K=\"kubectl --context ${CTX} -n ${NS}\"; stamp_replay_epoch'"
