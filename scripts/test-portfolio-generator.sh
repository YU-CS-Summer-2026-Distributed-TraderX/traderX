#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="${ROOT}/specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools"
PYTHONDONTWRITEBYTECODE=1 "${PYTHON:-python3}" -m unittest discover -s "${MODULE}" -p 'test_*.py' -v
