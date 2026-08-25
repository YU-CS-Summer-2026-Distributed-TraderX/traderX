#!/usr/bin/env bash
# build-stp-boundary-images.sh — build the version boundary yu13-stp-and-replace crosses, from
# TODAY's tree, on both sides.
#
#   fix = the generated order-matcher, untouched.
#   pre = the same tree with scripts/yu15/stp-boundary-revert.patch applied to a throwaway copy,
#         which removes self-trade prevention (ADR-057) and the /replace ingress (ADR-058).
#
# Both sides go through scripts/yu15/build-cluster-image.sh, so they share a gradle invocation, a
# Dockerfile and a base image, and the ONLY difference between the two binaries is the patch. That
# is checked, not assumed: this script diffs the two images' class trees at the end and fails if
# the difference is not exactly the two files the patch touches. Two identical images pass every
# assertion in the proof while proving nothing, and a synthesized pair makes that mistake easy.
#
# WHAT THIS REPLACED, and why. The boundary used to be traderx/cluster-node:yu15-pre-1k and
# :yu15-stp-1k -- built 2026-07-22 from a working tree carrying uncommitted changes, so no tree can
# rebuild them. Archaeology to identify the commits dead-ended. Lifting MAX_SECURITIES 64 -> 1024
# on them needed an ASM graft into the compiled classes. The runner's prep seeded through a
# mismatched gateway for months because nobody could rebuild the pair to test it. And the gap the
# pair crossed grew a week wider every week, so the proof exercised an upgrade nobody will perform
# while tip -> tip+1 went untested.
#
# THOSE IMAGES ARE STILL ON THIS HOST AND MUST STAY. :yu15-pre-1k / :yu15-stp-1k and the un-grafted
# :yu15-pre-orig64 / :yu15-stp-orig64 are the only surviving evidence of what the pair was, and
# they cannot be re-derived. This script points the proof somewhere else; it does not dispose of
# them, and retiring them is a separate decision.
#
# WHAT A SYNTHESIZED PAIR DOES NOT PROVE, stated here because the proof's PASS line cannot say it:
# recovery across a genuine historical format-and-capacity gap. The July pair spanned four snapshot
# formats and a 16x capacity change; this pair spans neither -- both sides write SNAPSHOT_FORMAT 7
# and hold MAX_SECURITIES 1024, so the roll is 7 -> 7 with an identical symbol table. That is the
# BEHAVIOURAL boundary and nothing else. Nothing currently covers the format-and-capacity one.
# (issues/open/nothing-proves-recovery-across-a-real-format-and-capacity-gap.md)
#
# Usage:  bash scripts/yu15/build-stp-boundary-images.sh
#         STP_PRE_TAG=... STP_FIX_TAG=... to override the tags (they must match IMAGE_PRE/IMAGE_FIX
#         in scripts/proofs/yu13-stp-and-replace.sh).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OM="${ROOT}/generated/code/target-generated/order-matcher"
PATCH="${ROOT}/scripts/yu15/stp-boundary-revert.patch"
PRE_TAG="${STP_PRE_TAG:-traderx/cluster-node:stp-boundary-pre}"
FIX_TAG="${STP_FIX_TAG:-traderx/cluster-node:stp-boundary-fix}"

fail() { echo "[fail] $*" >&2; exit 1; }

[[ -d "${OM}" ]] || fail "generated order-matcher missing; run: bash pipeline/generate-state-YU17-otc-rates.sh"
[[ -f "${PATCH}" ]] || fail "missing ${PATCH}"

# THE PATCH IS THE FIRST THING CHECKED, before an eight-minute build. --dry-run against the copy is
# the same test the real apply does, and a patch that no longer applies means the engine moved --
# which is the point of a synthesized boundary, not a failure. Re-cut it; never force it.
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
echo "[stp-boundary] copying the generated tree aside (the shared one is never patched)"
# --exclude build/.gradle: gradle output is large, stale, and about to be rebuilt from clean anyway.
rsync -a --exclude build --exclude .gradle "${OM}/" "${WORK}/order-matcher/"
patch -p1 -d "${WORK}/order-matcher" --forward --dry-run < "${PATCH}" >/dev/null \
  || fail "stp-boundary-revert.patch no longer applies to the generated tree.
  The engine moved under it. Re-cut the patch against today's source -- do NOT --force it: a
  fuzzed hunk silently produces a 'pre' image that is not the engine minus STP."
patch -p1 -d "${WORK}/order-matcher" --forward < "${PATCH}"

# fix FIRST, from the pristine tree. build-cluster-image.sh runs `gradlew clean bootJar` in whatever
# tree it is pointed at, so ordering is not load-bearing -- but building the unmodified side first
# means a failure here is a plain build break rather than something to blame on the patch.
echo
# DOCKER_NO_CACHE on BOTH sides. The `fix` side is today's tree unmodified, so its jar is usually
# byte-identical to whatever tip image was built last and docker hands back that image — Created and
# all. yu13-stp-and-replace's preflight then reads a Created older than the sources and refuses the
# pair it just asked for, which is a rebuild loop that never terminates. `pre` gets it too so the
# two sides are built the same way and neither can be the odd one out.
echo "[stp-boundary] building ${FIX_TAG} (today's tree, unmodified)"
DOCKER_NO_CACHE=1 CLUSTER_IMAGE="${FIX_TAG}" bash "${ROOT}/scripts/yu15/build-cluster-image.sh"

echo
echo "[stp-boundary] building ${PRE_TAG} (today's tree, STP + /replace removed)"
DOCKER_NO_CACHE=1 CLUSTER_IMAGE="${PRE_TAG}" OM_DIR="${WORK}/order-matcher" bash "${ROOT}/scripts/yu15/build-cluster-image.sh"

# ---------------------------------------------------------------------------------------------
# THE PAIR MUST DIFFER, AND ONLY IN THE BEHAVIOUR. This is not a tidiness check.
#
# yu13-stp-and-replace's step 6 exists because a dead engine passes step 5 (nothing filled) exactly
# as a working STP engine does. A pair built by the same script from the same tree adds the mirror
# hazard: two IDENTICAL images pass every step including step 6, and report a boundary that was
# never crossed. Assert the difference here, where the pair is produced, rather than hoping a
# reader notices two matching digests in a proof log.
#
# Compared unpacked, not by image digest: Dockerfile.cluster explodes the boot jar to
# /opt/app/classes, and image digests differ on layer timestamps alone even for identical content.
# md5sum and grep are in the eclipse-temurin JRE base; javap and jar are NOT (it is a JRE, not a
# JDK), which is why this compares file hashes and constant-pool strings, not disassembly.

class_tree() { # class_tree <image>  -> "<md5>  ./path/To.class" per line, sorted by path
  docker run --rm --entrypoint sh "$1" -c \
    'cd /opt/app/classes && find . -name "*.class" -exec md5sum {} +' | sort -k2
}
marker() { # marker <image> <class-path> <string>  -> count of occurrences in the class file
  docker run --rm --entrypoint sh "$1" -c "grep -c '$3' /opt/app/classes/$2 || true"
}

echo
echo "[stp-boundary] checking the pair"

# (1) THE BEHAVIOUR ITSELF, read out of the constant pools. This is the check that means something:
# `preventSelfTrade` is the method cross() calls instead of filling, and "/replace" is the context
# string the gateway registers its replace route under. Absent on `pre`, present on `fix`, or the
# proof's step 2 and step 3 are asserting against an engine that never lost the behaviour.
ME=finos/traderx/ordermatcher/lmax/MatchingEngine.class
GW=finos/traderx/ordermatcher/cluster/ClusterGatewayMain.class
for spec in "${PRE_TAG}|${ME}|preventSelfTrade|0" "${PRE_TAG}|${GW}|/replace|0" \
            "${FIX_TAG}|${ME}|preventSelfTrade|1" "${FIX_TAG}|${GW}|/replace|1"; do
  IFS='|' read -r img cls str want <<<"${spec}"
  got="$(marker "${img}" "${cls}" "${str}")"
  [[ "${got}" == "${want}" ]] || fail "${img}: '${str}' in ${cls##*/} -> ${got}, expected ${want}.
  The patch did not produce the boundary this proof claims to cross."
done
echo "[ok] pre lacks preventSelfTrade and the /replace route; fix has both"

# (2) AND NOTHING ELSE MOVED. Confined to the two compilation units the patch touches -- which is
# FIVE class files, not two, and the extra three are not a bug:
#     ClusterGatewayMain.class, $1, $Inflight, $PendingOrder, MatchingEngine.class
# The patch deletes one line at ClusterGatewayMain.java:338, which shifts every LineNumberTable
# entry in the classes compiled from the rest of that file by exactly 1. Verified 2026-08-23 with
# `javap -v`: the inner classes' bytecode is byte-identical and ONLY LineNumberTable differs. A
# whitelist of exact filenames would therefore be wrong AND brittle; the invariant that matters is
# that no class from any OTHER source file moved.
# `|| true`: diff exits 1 when the files differ, which is the expected case here, and this script
# runs under `set -o pipefail`.
DIFFERING="$( { diff <(class_tree "${PRE_TAG}") <(class_tree "${FIX_TAG}") || true; } \
  | sed -n 's|^[<>] *[0-9a-f]\{32\}  \./||p' | sort -u)"
if [[ -z "${DIFFERING}" ]]; then
  fail "${PRE_TAG} and ${FIX_TAG} have IDENTICAL class trees.
  Two identical images pass every assertion in yu13-stp-and-replace -- step 6 included -- while
  proving no boundary was crossed."
fi
STRAY="$(grep -vE '^finos/traderx/ordermatcher/(lmax/MatchingEngine|cluster/ClusterGatewayMain)(\$[A-Za-z0-9]+)?\.class$' \
  <<<"${DIFFERING}" || true)"
if [[ -n "${STRAY}" ]]; then
  fail "the pair differs in classes compiled from source the patch does not touch:
$(sed 's/^/    /' <<<"${STRAY}")
  A boundary that moves more than the behaviour under proof is not the boundary this proof claims
  to cross. Usually means the two builds saw different source -- check for a concurrent
  generate-state (pgrep -fl generate-state) and rebuild."
fi
echo "[ok] the only classes that differ come from the two files the patch edits:"
sed 's/^/       /' <<<"${DIFFERING}"

echo
echo "[ok] ${PRE_TAG}  (no STP, no /replace route)"
echo "[ok] ${FIX_TAG}  (today's tree)"
echo "     Next: bash scripts/yu15/run-proofs.sh yu13-stp-and-replace"
