#!/usr/bin/env python3
"""Run the current local CPU portfolio acceptance with an installed engine candidate."""
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools"))
from local_cpu import main

main()
