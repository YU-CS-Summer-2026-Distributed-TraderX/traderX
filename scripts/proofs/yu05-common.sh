#!/usr/bin/env bash
# YU05 post-trade compliance — shared helpers (sourced by the yu05-*.sh proof scripts).
# Every YU05 endpoint requires a real JWT; mint one with the dev-token endpoint.
#
# Prereqs (separate terminals):
#   kubectl port-forward -n traderx deploy/trade-processor 18091:18091 --context "${CTX:-kind-traderx-yu12-cluster}"
#   (order-matcher is reached via the edge-proxy at localhost:8080/order-matcher)
set -uo pipefail
TP=${TRADE_PROCESSOR_URL:-http://localhost:18091}
# No edge-proxy on the cluster rig, so the matcher is reached directly. svc/order-matcher there
# fronts the cluster gateway; on the state-014 rig, set ORDER_MATCHER_URL to the edge-proxy path.
OM=${ORDER_MATCHER_URL:-http://localhost:18110}
CTX=${CTX:-kind-traderx-yu12-cluster}
K="kubectl -n traderx --context $CTX"

# The dev-token master secret is a Kubernetes Secret, and the two rigs hold different values, so
# neither literal is safe to hardcode -- a wrong one fails as an opaque 401 from /auth/dev-token
# with nothing saying the secret was the problem. Read it from whichever rig CTX points at, and
# fall back to the historical literal only if the lookup finds nothing.
MASTER=${AUTH_MASTER_SECRET:-$($K get secret auth-secrets \
  -o "jsonpath={.data.dev-token-master-secret}" 2>/dev/null | base64 -d 2>/dev/null)}
MASTER=${MASTER:-dev-token-master-secret}

# The MariaDB carrying the trade-processor projection: "eod-price-db" on the cluster rig,
# "database" on the state-014 rig.
DB_DEPLOY=${DB_DEPLOY:-eod-price-db}

# dbq <sql>  -> tab-separated rows, no header (for reading the MariaDB projection; settlement state
# has no HTTP read endpoint, it lives only in the trade-processor projection — FR-PTC07).
dbq(){ $K exec "deploy/${DB_DEPLOY}" -- sh -c "mariadb -utraderx -ptraderx traderx -N -B -e \"$1\"" 2>/dev/null; }

# mint <admin:true|false> <accounts-json e.g. [] or [22214]>  -> prints the raw JWT
mint(){
  local r; r=$(curl -s -m8 -X POST "$TP/auth/dev-token" \
    -H "X-Auth-Master-Secret: $MASTER" -H "Content-Type: application/json" \
    -d "{\"subject\":\"demo\",\"accounts\":$2,\"admin\":$1,\"ttlSeconds\":600}")
  # dev-token may return the raw token or {"token":...}/{"accessToken":...}; handle both. TO-VERIFY shape.
  printf '%s' "$r" | python3 -c "import sys,json
s=sys.stdin.read().strip()
try:
    d=json.loads(s); print(d.get('token') or d.get('accessToken') or d.get('jwt') or s)
except Exception:
    print(s.strip('\"'))"
}

# hs <METHOD> <url> <token>  -> prints just the HTTP status code (for 200/401/403 checks)
hs(){ curl -s -m8 -o /dev/null -w "%{http_code}" -X "$1" "$2" -H "Authorization: Bearer ${3:-}"; }

# jfield <json-on-stdin> <python-expr on d>  -> prints field or empty on parse failure
jfield(){ python3 -c "import sys,json
try:
    d=json.load(sys.stdin); print($1)
except Exception:
    print('')" 2>/dev/null; }
