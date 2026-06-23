#!/usr/bin/env bash

# Resolve the numeric policy base for both numbered states (009b-*) and named
# continuation states (in-memory-risk-gateway). Named states declare
# `numericBase` in catalog/state-catalog.json so ordering-based installers do
# not need to encode aliases.
state_numeric_base() {
  local state_id="$1"
  local catalog_path="$2"
  local prefix="${state_id%%-*}"

  if [[ "${prefix}" =~ ^[0-9]+[a-z]?$ ]]; then
    prefix="${prefix%%[a-z]*}"
    printf '%s\n' "${prefix}"
    return 0
  fi

  if [[ ! -f "${catalog_path}" ]] || ! command -v jq >/dev/null 2>&1; then
    return 1
  fi

  prefix="$(jq -r --arg id "${state_id}" '.states[] | select(.id == $id) | .numericBase // empty' "${catalog_path}")"
  if [[ "${prefix}" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "${prefix}"
    return 0
  fi
  return 1
}
