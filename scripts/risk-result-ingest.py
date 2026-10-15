#!/usr/bin/env python3
"""Import a verified external Batch result or read its local status, without cloud access."""
import sys
from pathlib import Path
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] /
                     'specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools'))
from result_ingest import main
main()
