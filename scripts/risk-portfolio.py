#!/usr/bin/env python3
"""Convenience entrypoint to the authoritative YU18 local portfolio tooling."""
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools"))
from cli import main

raise SystemExit(main())
