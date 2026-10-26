#!/usr/bin/env python3
"""Risk-engine half of the VaR/ES end-to-end proof (see scripts/proofs/yu18-var-es-e2e.sh)."""
import sys
from pathlib import Path
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] /
                     'specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools'))
from var_es_e2e import main
raise SystemExit(main())
