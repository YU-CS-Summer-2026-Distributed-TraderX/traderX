#!/usr/bin/env bash
# YU09 — PROOF (no cluster needed): the production deploy path carries no literal database or
# JWT/dev-token credential — every one is a secretKeyRef into mariadb-credentials / auth-secrets
# (FR-OH01/02/03, SC-OH03). Scope = exactly what this state hardened: the five rendered Deployments
# plus the production StatefulSet. A repo checkout of the deploy path never exposes a working credential.
#
# It ALSO scans the rest of cluster-addons and reports any literal creds left OUTSIDE that path as an
# explicit NOTE — honest about FR-OH04's "anywhere in the repository" wording vs. what SC-OH03 verifies.
#
# Usage: bash yu09-no-literal-creds.sh
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$here/../.." && pwd)"
cd "$ROOT" || exit 1

BASE=specs/YU09-ops-hardening/generation/runtime-overrides/kubernetes-runtime/manifests/base
STS=cluster-addons/order-matcher-statefulset.yaml
CREDKEYS='MARIADB_USER|MARIADB_PASSWORD|MARIADB_ROOT_PASSWORD|DATABASE_DBUSER|DATABASE_DBPASS|AUTH_JWT_SECRET|AUTH_DEV_TOKEN_MASTER_SECRET'
# a literal cred = one of those keys immediately followed by a `value:` line (MARIADB_DATABASE /
# DATABASE_NAME = the schema name, legitimately literal, and are NOT in CREDKEYS).
literal_creds() { grep -hA1 -E "name: ($CREDKEYS)" "$@" 2>/dev/null | grep -cE '^\s*value:'; }

echo "── credential hygiene: production deploy path uses Secrets, no literals (SC-OH03) ──"

FILES=(); for f in database order-matcher trade-processor account-service position-service; do
  FILES+=("$BASE/${f}-deployment.yaml"); done
FILES+=("$STS")

leaks=$(literal_creds "${FILES[@]}")
printf "   %-44s %s\n" "literal cred values in the 5 Deployments + STS" \
  "$([ "$leaks" -eq 0 ] && echo '0  ✔' || echo "$leaks  ✘")"

# each cred key resolves via secretKeyRef, with evidence
echo "   ── cred env → secretKeyRef ──"
for k in MARIADB_PASSWORD MARIADB_ROOT_PASSWORD DATABASE_DBPASS AUTH_JWT_SECRET AUTH_DEV_TOKEN_MASTER_SECRET; do
  hit=$(for f in "${FILES[@]}"; do grep -qA3 "name: $k" "$f" 2>/dev/null && grep -A3 "name: $k" "$f" | grep -q secretKeyRef && { basename "$f"; break; }; done)
  [ -n "$hit" ] && printf "   %-32s secretKeyRef ✔  %s\n" "$k" "$hit"
done

# honesty leg: literal creds anywhere else under cluster-addons (FR-OH04 "anywhere") — reported, not scored
OTHER=$(grep -rlE "name: ($CREDKEYS)" cluster-addons --include='*.yaml' 2>/dev/null | grep -v "$STS")
other_leaks=0; [ -n "$OTHER" ] && other_leaks=$(literal_creds $OTHER)
echo "   ── FR-OH04 scope note (outside the hardened deploy path) ──"
if [ "$other_leaks" -gt 0 ]; then
  printf "   %-44s %s\n" "NOTE: literal creds elsewhere in cluster-addons" "$other_leaks  ⚠"
  grep -rnA1 -E "name: ($CREDKEYS)" $OTHER 2>/dev/null | grep -B1 -E '^\S+[:-][0-9]+-\s*value:' | grep -E 'name:|value:' \
    | sed 's/^/      /' | head -12
  echo "      ↳ isolated YU03 CI/CD staging sandbox (own namespace + provision-yu03-staging-secret.sh),"
  echo "        not the kind/GKE deploy path SC-OH03 hardens — dev-default 'traderx' value. See notes."
else
  printf "   %-44s %s\n" "no literal creds anywhere in cluster-addons" "✔"
fi

echo
[ "$leaks" -eq 0 ] && echo "   → the production deploy path exposes no credential; all via Secrets ✔" \
                  || echo "   → literal credentials in the hardened path ✘"
