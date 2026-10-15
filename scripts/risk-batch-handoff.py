#!/usr/bin/env python3
"""Prepare and admit pinned Cloud Build source and result receipts."""
import sys
from pathlib import Path
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] /
                     'specs/YU18-risk-integration/generation/runtime-overrides/risk-portfolio-tools'))
from batch_handoff import main
raise SystemExit(main())
