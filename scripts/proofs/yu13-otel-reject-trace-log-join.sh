#!/usr/bin/env bash
# OTEL-01 follow-up proof: (1) a REJECTED order is traced even when head sampling threw it away,
# and both tiers escalate together so the trace is whole; (2) that order's LOG LINE and its TRACE
# carry the same id, so "Logs for this span" and the TraceID link on a log line both land.
#
# WHY THIS IS FALSIFIABLE, and not just "a trace and a log appeared":
#
#   * Every id is computed HERE, in Python, from the ClOrdID alone — the FNV-1a client key hash and
#     OrderTrace's splitmix64, reimplemented with no input from any server. Tempo and Loki are then
#     asked for exactly those ids. A build where the gateway and the member disagreed, or where the
#     log line derived the id differently from the span emitter, 404s or mismatches here.
#
#   * It runs with head sampling ON (mask 127 by default, not the kind default of 0) and picks
#     ClOrdIDs on BOTH sides of the head verdict on purpose:
#       - an UNSAMPLED + REJECTED order  -> MUST be in Tempo   (escalation did the work)
#       - an UNSAMPLED + ACCEPTED order  -> MUST NOT be in Tempo (escalation, not "trace everything")
#     Without the negative case, a build that quietly started tracing every order would pass.
#
#   * It asserts the member spans parent to the PREDICTED cluster.consensus span id. Escalation is
#     the one change that could plausibly desynchronise the two tiers — the gateway escalating and
#     the member not would look like "it works" in a span list and be a half-trace in the waterfall.
#
#   * It checks the Grafana datasources actually provisioned the join both ways, because the
#     sibling finding on this deliverable was a panel that had never worked in any environment
#     since it shipped. Wiring that is present in a file and absent from the running Grafana is the
#     failure mode this deliverable already has a history of.
#
# The mask is flipped on the deployed gateway AND members (it must match on both) and restored on
# exit, including on failure.
#
# Usage:
#   kubectl --context kind-traderx-yu12-cluster -n traderx port-forward svc/order-matcher 18110:18110 &
#   kubectl --context kind-traderx-yu12-cluster -n traderx port-forward svc/tempo 3200:3200 &
#   kubectl --context kind-traderx-yu12-cluster -n traderx port-forward svc/loki 3100:3100 &
#   kubectl --context kind-traderx-yu12-cluster -n traderx port-forward svc/grafana 3000:3000 &
#   bash scripts/proofs/yu13-otel-reject-trace-log-join.sh
set -euo pipefail

MATCHER_URL="${MATCHER_URL:-http://localhost:18110}"
TEMPO_URL="${TEMPO_URL:-http://localhost:3200}"
LOKI_URL="${LOKI_URL:-http://localhost:3100}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000/grafana}"
# Anonymous access is Viewer, and /api/datasources is Admin-only — so this check has to
# authenticate. Defaults are the kind rig's own (GF_SECURITY_ADMIN_USER/PASSWORD on the deployment).
GRAFANA_USER="${GRAFANA_USER:-admin}"
GRAFANA_PASSWORD="${GRAFANA_PASSWORD:-admin}"
NS="${NS:-traderx}"
KCTX="${KCTX:-${CTX:-kind-traderx-yu12-cluster}}"   # CTX accepted too, so one env var drives every proof
K="kubectl --context ${KCTX} -n ${NS}"
MASK="${OTEL_SAMPLE_MASK:-127}"
OWN_PORT="${OWN_PORT:-18111}"   # private gateway forward, so the operator's 18110 is not touched
SEEDED_ACCOUNT="${SEEDED_ACCOUNT:-22214}"
UNKNOWN_ACCOUNT="${UNKNOWN_ACCOUNT:-987654}"
TAG="reject-$$-$(date +%s)"

fail() { echo "[FAIL] $*" >&2; exit 1; }
ok() { echo "[ok] $*"; }

command -v python3 >/dev/null || fail "python3 required"
curl -sf --max-time 5 "${MATCHER_URL}/ready" >/dev/null || fail "gateway not ready at ${MATCHER_URL}"

# PRECONDITIONS, checked BEFORE the mask roll below — that roll restarts the gateway and all three
# members and costs minutes, and every one of them would be spent to arrive at an empty Tempo.
# start-cluster-kind.sh deploys only the trading tier, so the observability stack is absent by
# default and the trace pipeline dies SILENTLY: orders book normally, spans go nowhere, and the only
# symptom is an empty Tempo. Probe the three services this proof actually reads from; the collector
# it writes to is in-cluster and never forwarded, so it is not the dependency to guard here.
need_obs() { # url  name  port-forward-target
  local i rc
  for i in $(seq 1 "${OBS_WAIT_TRIES:-10}"); do
    rc=0; curl -sf --max-time 5 "$1" >/dev/null 2>&1 || rc=$?
    if [[ ${rc} -eq 0 ]]; then return 0; fi
    # 7 = nothing listening. No stack and no port-forward look identical from here, and neither
    # heals on its own — retrying is theatre. Anything else (a 503 from a Tempo that is still
    # coming up after a fresh forward) is worth waiting out.
    if [[ ${rc} -eq 7 ]]; then break; fi
    sleep 2
  done
  echo "[FAIL] $2 unreachable at $1 — the observability stack is not up, or not forwarded." >&2
  echo "[hint] bash scripts/yu15/start-observability-kind.sh" >&2
  echo "[hint] ${K} port-forward $3 &" >&2
  exit 1
}
need_obs "${TEMPO_URL}/ready" Tempo "svc/tempo 3200:3200"
need_obs "${LOKI_URL}/ready" Loki "svc/loki 3100:3100"
need_obs "${GRAFANA_URL}/api/health" Grafana "svc/grafana 3000:3000"

# ----- head sampling must actually be ON, or there is nothing for escalation to rescue ----------
ORIG_MASK="$(${K} get deployment/cluster-gateway \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="OTEL_SAMPLE_MASK")].value}')"
ORIG_MASK="${ORIG_MASK:-0}"

OWN_FORWARD_PID=""

cleanup() {
  [[ -n "${OWN_FORWARD_PID}" ]] && kill "${OWN_FORWARD_PID}" 2>/dev/null
  [[ "${ORIG_MASK}" == "${MASK}" ]] && return 0
  echo "[restore] OTEL_SAMPLE_MASK back to ${ORIG_MASK}"
  ${K} set env deployment/cluster-gateway "OTEL_SAMPLE_MASK=${ORIG_MASK}" >/dev/null
  ${K} set env statefulset/order-matcher-cluster "OTEL_SAMPLE_MASK=${ORIG_MASK}" >/dev/null
  ${K} rollout status deployment/cluster-gateway --timeout=300s >/dev/null || true
  ${K} rollout status statefulset/order-matcher-cluster --timeout=300s >/dev/null || true
  echo "[note] your own gateway port-forward died with the rolled pod — restart it:"
  echo "       ${K} port-forward svc/order-matcher 18110:18110 &"
}
trap cleanup EXIT

# This script ROLLS the gateway (the mask is read once at startup), and a kubectl port-forward binds
# one pod for its lifetime — so the operator's forward on 18110 dies the moment the roll starts, and
# every probe after it reads as "the gateway never came back". It did; the tunnel went. So the
# script brings up its own forward on a private port for the rolled half of the run.
own_forward() {
  kill "${OWN_FORWARD_PID}" 2>/dev/null
  ${K} port-forward deployment/cluster-gateway "${OWN_PORT}:18110" >/dev/null 2>&1 &
  OWN_FORWARD_PID=$!
  MATCHER_URL="http://localhost:${OWN_PORT}"
  for _ in $(seq 1 60); do
    curl -sf --max-time 3 "${MATCHER_URL}/ready" >/dev/null 2>&1 && return 0
    sleep 2
  done
  return 1
}

if [[ "${ORIG_MASK}" != "${MASK}" ]]; then
  # BOTH tiers, always. A mask set on one side only is the documented way to manufacture
  # half-traces: each derives the verdict independently, so they must be given the same one.
  echo "[setup] OTEL_SAMPLE_MASK ${ORIG_MASK} -> ${MASK} on gateway AND members"
  ${K} set env deployment/cluster-gateway "OTEL_SAMPLE_MASK=${MASK}" >/dev/null
  ${K} set env statefulset/order-matcher-cluster "OTEL_SAMPLE_MASK=${MASK}" >/dev/null
  ${K} rollout status deployment/cluster-gateway --timeout=300s
  ${K} rollout status statefulset/order-matcher-cluster --timeout=300s
  # Own forward, then wait on readiness through it — the gateway also has to reconnect its cluster
  # client after the members roll, which is later than "pod Ready".
  own_forward || fail "gateway did not come back ready on its own port-forward"
  ok "gateway ready again on ${MATCHER_URL} after the mask roll"
fi

# ----- choose ClOrdIDs on both sides of the head verdict, before submitting anything ------------
# Both are chosen to be OUTSIDE the head sample. One will be rejected (unknown account) and one
# accepted, so the only difference between them is the outcome.
read -r REJECT_CLORDID ACCEPT_CLORDID <<<"$(TAG="${TAG}" MASK="${MASK}" python3 - <<'PY'
import os
M = (1 << 64) - 1
SAMPLE_SALT = 0x2545F4914F6CDD1D

def client_order_key(s):
    h = 0xcbf29ce484222325
    for ch in s:
        h = ((h ^ ord(ch)) * 0x100000001b3) & M
    return h or 1

def mix(x):
    z = (x + 0x9E3779B97F4A7C15) & M
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M
    return (z ^ (z >> 31)) & M

tag, mask = os.environ["TAG"], int(os.environ["MASK"])
picked = []
i = 0
while len(picked) < 2:
    i += 1
    cid = f"{tag}-{i}"
    if (mix(client_order_key(cid) ^ SAMPLE_SALT) & mask) != 0:   # NOT in the head sample
        picked.append(cid)
print(picked[0], picked[1])
PY
)"
ok "chose two ClOrdIDs the head sample rejects: ${REJECT_CLORDID} / ${ACCEPT_CLORDID}"

# A FRESH ticker per run, matching every sibling yu13-* proof (DUP/RPL/RM/STP + timestamp).
#
# This used to trade AAPL at a hardcoded 150.0 and seed AAPL at 150 to anchor it. That seed cannot
# work on a rig where AAPL has already traded: per ADR-051 a price tick seeds the mark ONLY while no
# trade has printed, and after that the LAST TRADE PRICE is the mark. The quickstart crosses AAPL at
# 241.80, so 150.0 fell outside the collar and BOTH arms came back PRICE_COLLAR — including the arm
# that has to be ACCEPTED, which the script correctly refused to proceed on ("the negative case
# would be vacuous"). Re-seeding does not fix it; only a security that has never traded does.
TICKER="${TICKER:-RJT$(date +%H%M%S)}"
PX="${PX:-150.0}"
echo "[seed] ${TICKER} @ ${PX} for account ${SEEDED_ACCOUNT} (fresh ticker: no trade has printed, so the tick IS the mark)"
curl -s -m20 -X POST "${MATCHER_URL}/seed" -H 'Content-Type: application/json' \
  -d "{\"accountId\":${SEEDED_ACCOUNT},\"tickers\":\"${TICKER}\",\"price\":${PX}}" >/dev/null

submit() { # clOrdId account -> response body
  curl -s --max-time 15 -X POST "${MATCHER_URL}/orders" -H 'Content-Type: application/json' \
    -d "{\"accountId\":$2,\"ticker\":\"${TICKER}\",\"side\":\"Buy\",\"quantity\":10,\"limitPrice\":${PX},\"clientOrderId\":\"$1\"}"
}

reject_body="$(submit "${REJECT_CLORDID}" "${UNKNOWN_ACCOUNT}")"
accept_body="$(submit "${ACCEPT_CLORDID}" "${SEEDED_ACCOUNT}")"
echo "[run] reject arm: ${reject_body}"
echo "[run] accept arm: ${accept_body}"
[[ "${reject_body}" == *'"kind":2'* ]] ||
  fail "the reject arm was not rejected (kind 2): ${reject_body} — pick an account that is really unknown"
[[ "${accept_body}" == *'"kind":2'* ]] &&
  fail "the accepted control was rejected too: ${accept_body} — the negative case would be vacuous"
ok "one rejected order and one accepted order, both outside the head sample"

echo "[run] waiting for the asynchronous exporter and promtail to flush"
sleep "${FLUSH_WAIT:-12}"

REJECT_CLORDID="${REJECT_CLORDID}" ACCEPT_CLORDID="${ACCEPT_CLORDID}" \
TEMPO_URL="${TEMPO_URL}" LOKI_URL="${LOKI_URL}" GRAFANA_URL="${GRAFANA_URL}" \
GRAFANA_USER="${GRAFANA_USER}" GRAFANA_PASSWORD="${GRAFANA_PASSWORD}" python3 - <<'PY'
import base64, json, os, sys, time, urllib.error, urllib.parse, urllib.request

M = (1 << 64) - 1
TRACE_SALT = 0x5851F42D4C957F2D
CLUSTER_SALT = 0x14057B7EF767814F

def client_order_key(s):                      # ClusterGatewayMain.clientOrderKey (FNV-1a)
    h = 0xcbf29ce484222325
    for ch in s:
        h = ((h ^ ord(ch)) * 0x100000001b3) & M
    return h or 1

def mix(x):                                   # OrderTrace.mix (splitmix64 finalizer)
    z = (x + 0x9E3779B97F4A7C15) & M
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M
    return (z ^ (z >> 31)) & M

def trace_id(clordid):                        # OrderTrace.traceIdHex, independently
    k = client_order_key(clordid)
    return f"{mix(k) or 1:016x}{mix(k ^ TRACE_SALT) or 1:016x}"

def consensus_span(clordid):
    return f"{mix(client_order_key(clordid) ^ CLUSTER_SALT) or 1:016x}"

def get(url, timeout=15, auth=None):
    req = urllib.request.Request(url)
    if auth:
        token = base64.b64encode(auth.encode()).decode()
        req.add_header("Authorization", f"Basic {token}")
    return json.load(urllib.request.urlopen(req, timeout=timeout))

reject_id, accept_id = os.environ["REJECT_CLORDID"], os.environ["ACCEPT_CLORDID"]
tempo, loki, grafana = os.environ["TEMPO_URL"], os.environ["LOKI_URL"], os.environ["GRAFANA_URL"]
failures = []

# --- 1. the rejected order IS in Tempo, whole, despite failing the head sample ------------------
predicted = trace_id(reject_id)
predicted_parent = consensus_span(reject_id)
try:
    doc = get(f"{tempo}/api/traces/{predicted}")
except urllib.error.HTTPError as e:
    doc = None
    failures.append(f"Tempo has no trace {predicted} for the REJECTED order (HTTP {e.code}) — head "
                    f"sampling dropped it and nothing escalated it back")

if doc:
    spans = []
    for batch in doc.get("batches", []):
        svc = next((a["value"]["stringValue"] for a in batch["resource"]["attributes"]
                    if a["key"] == "service.name"), "?")
        for scope in batch.get("scopeSpans", []):
            for s in scope.get("spans", []):
                spans.append({
                    "svc": svc,
                    "name": s["name"],
                    "id": base64.b64decode(s["spanId"]).hex(),
                    "parent": base64.b64decode(s["parentSpanId"]).hex() if s.get("parentSpanId") else "",
                })
    services = {s["svc"] for s in spans}
    consensus = [s for s in spans if s["name"] == "cluster.consensus"]
    member_spans = [s for s in spans if s["svc"].endswith("member")]
    if len(spans) != 5:
        failures.append(f"rejected order: expected 5 spans, got {len(spans)} "
                        f"({sorted(s['name'] for s in spans)}) — one tier escalated and the other "
                        f"did not, which is the half-trace this design must not produce")
    if len(services) != 2:
        failures.append(f"rejected order: expected gateway+member, got {sorted(services)}")
    if not consensus:
        failures.append("rejected order: no cluster.consensus span")
    elif consensus[0]["id"] != predicted_parent:
        failures.append(f"rejected order: consensus span {consensus[0]['id']} != predicted "
                        f"{predicted_parent}")
    if len(member_spans) != 2:
        failures.append(f"rejected order: expected 2 member spans, got {len(member_spans)} — the "
                        f"member did not escalate with the gateway")
    elif consensus and {s['parent'] for s in member_spans} != {consensus[0]['id']}:
        failures.append("rejected order: member spans are not children of cluster.consensus")
    if not failures:
        print(f"[ok] REJECTED order {reject_id}: full 5-span trace {predicted} across "
              f"{sorted(services)}, member spans parented to the PREDICTED consensus span "
              f"{predicted_parent} — escalated at the head, on both sides, with nothing carried")

# --- 2. the accepted order outside the sample is NOT traced (escalation, not trace-everything) ---
control = trace_id(accept_id)
try:
    get(f"{tempo}/api/traces/{control}")
    failures.append(f"an ACCEPTED order outside the head sample was traced ({control}) — this is "
                    f"not error sampling, the build is tracing everything and the proof above is "
                    f"vacuous")
except urllib.error.HTTPError as e:
    if e.code == 404:
        print(f"[ok] accepted order {accept_id} outside the head sample is NOT in Tempo — the "
              f"rejected trace above exists because it was rejected, not because sampling is off")
    else:
        failures.append(f"unexpected HTTP {e.code} probing the control trace {control}")

# --- 3. the LOG LINE carries the same derived id, and names the order ----------------------------
end = time.time_ns()
start = end - 30 * 60 * 1_000_000_000
query = urllib.parse.urlencode({
    "query": '{namespace="traderx"} |= "trace=' + predicted + '"',
    "start": start, "end": end, "limit": 50,
})
lines = []
for attempt in range(10):                     # promtail ships on its own schedule; poll, briefly
    try:
        res = get(f"{loki}/loki/api/v1/query_range?{query}")
        lines = [v[1] for r in res.get("data", {}).get("result", []) for v in r.get("values", [])]
    except urllib.error.HTTPError as e:
        failures.append(f"Loki query failed: HTTP {e.code}")
        break
    if lines:
        break
    time.sleep(3)

if not lines:
    failures.append(f"Loki has no line containing trace={predicted} — the log line and the spans "
                    f"do not agree on the trace id, so 'Logs for this span' cannot land")
else:
    line = lines[0]
    if "ORDER-REJECT" not in line:
        failures.append(f"the matching line is not the reject line: {line}")
    if reject_id not in line:
        failures.append(f"the reject line does not name the ClOrdID {reject_id}: {line}")
    if not failures:
        print(f"[ok] Loki returns the order's own log line for the trace id PREDICTED from its "
              f"ClOrdID:\n     {line.strip()}")

# --- 4. Grafana really provisioned the join, in both directions ----------------------------------
try:
    creds = f"{os.environ['GRAFANA_USER']}:{os.environ['GRAFANA_PASSWORD']}"
    datasources = get(f"{grafana}/api/datasources", auth=creds)
    by_uid = {d["uid"]: d for d in datasources}
    tempo_ds = by_uid.get("tempo", {}).get("jsonData", {})
    loki_ds = by_uid.get("loki", {}).get("jsonData", {})
    t2l = tempo_ds.get("tracesToLogsV2") or {}
    if "${__trace.traceId}" not in (t2l.get("query") or ""):
        failures.append(f"Grafana's Tempo datasource has no tracesToLogsV2 query on the trace id "
                        f"(got {t2l!r}) — 'Logs for this span' will query an empty selector, which "
                        f"is exactly how the v1 config failed silently")
    derived = loki_ds.get("derivedFields") or []
    if not any("[0-9a-f]{32}" in (d.get("matcherRegex") or "") for d in derived):
        failures.append(f"Grafana's Loki datasource has no derivedField for the trace id "
                        f"(got {derived!r}) — a log line will not link back to its trace")
    if not failures:
        print("[ok] Grafana has the join provisioned BOTH ways: Tempo tracesToLogsV2 filters on "
              "${__trace.traceId}, Loki derivedFields links trace=<32 hex> into Tempo")
except Exception as e:                        # noqa: BLE001 - any failure here is a real failure
    failures.append(f"could not read Grafana datasources at {grafana}: {e}")

if failures:
    for f in failures:
        print(f"[FAIL] {f}", file=sys.stderr)
    sys.exit(1)
PY

echo
echo "[PASS] a rejected order is traced end to end even though head sampling threw it away, both"
echo "       tiers escalated it independently, and its log line and its trace carry the same"
echo "       derived id — with nothing about tracing in the replicated log."
