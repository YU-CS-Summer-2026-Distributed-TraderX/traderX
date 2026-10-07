"""File generation works with Python standard library; engine imports are opt-in."""
import argparse
import json
import subprocess
import sys
from pathlib import Path

from corpus import ENGINE, MAX_BYTES, digest, generate, load_request, verify_entry, verify_manifest


def main():
    p = argparse.ArgumentParser(description="Synthetic USD Treasury generator and local single-POST client")
    sub = p.add_subparsers(dest="command", required=True)
    g = sub.add_parser("generate")
    g.add_argument("output", type=Path)
    for name, default in (("seed", 42), ("trades", 100), ("portfolios", 1), ("samples", 0), ("max-bytes", MAX_BYTES)):
        g.add_argument("--" + name, type=int, default=default)
    g.add_argument("--asof", default="2026-10-07")
    g.add_argument("--rate", type=float, default=0.03)
    g.add_argument("--mode", choices=("repeated", "heterogeneous"), default="repeated")
    v = sub.add_parser("validate")
    v.add_argument("directory", type=Path)
    v.add_argument("--engine-root", type=Path)
    for command in ("submit", "poll", "recover"):
        c = sub.add_parser(command)
        c.add_argument("request", type=Path)
        c.add_argument("--origin", required=True)
        c.add_argument("--evidence", type=Path, required=True)
        c.add_argument("--submission-id")
        c.add_argument("--job-id")
        c.add_argument("--manifest", type=Path)
        c.add_argument("--timeout", type=float, default=10)
        c.add_argument("--deadline", type=float, default=120)
        c.add_argument("--interval", type=float, default=0.5)
        c.add_argument("--max-polls", type=int, default=1000)
    args = p.parse_args()
    try:
        if args.command == "generate":
            m = generate(args.output, seed=args.seed, trades=args.trades, portfolios=args.portfolios,
                         asof=args.asof, rate=args.rate, mode=args.mode, samples=args.samples, max_bytes=args.max_bytes)
            print(json.dumps({"synthetic": True, "output": str(args.output.resolve()),
                              "corpus_id": m["corpus_id"], "request_bytes": m["request_bytes"]}))
        elif args.command == "validate":
            m = verify_manifest(args.directory)
            engine = None
            if args.engine_root:
                root = args.engine_root.resolve()
                revision = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
                if revision != ENGINE or subprocess.call(["git", "-C", str(root), "diff", "--quiet", "HEAD", "--", "engine"]):
                    raise ValueError("engine schema proof requires pinned revision " + ENGINE)
                # Avoid writes to the read-only engine checkout.
                sys.dont_write_bytecode = True
                sys.path.insert(0, str(root))
                from engine.api.market_schemas import MarketPortfolioRequestSchema
                from engine.portfolio.market_path import validate_request
                engine = (MarketPortfolioRequestSchema, validate_request)
            for item in m["files"]:
                body = load_request(args.directory / item["path"])
                verify_entry(body, item)
                if engine:
                    engine[1](engine[0].model_validate(body).to_dataclass())
            print(json.dumps({"files_validated": len(m["files"]), "engine_schema_validated": bool(engine),
                              "financial_validation": False}))
        else:
            if args.command == "submit" and args.job_id or args.command == "poll" and not args.job_id:
                raise ValueError("submit takes no job_id; poll requires job_id")
            load_request(args.request)
            identity = args.submission_id
            if args.manifest:
                m = verify_manifest(args.manifest.parent)
                matches = [i for i in m["files"] if args.request.resolve() == (args.manifest.parent / i["path"]).resolve()]
                if len(matches) != 1 or digest(args.request) != matches[0]["sha256"]:
                    raise ValueError("request does not match manifest")
                if identity and identity != matches[0]["submission_id"]:
                    raise ValueError("submission identity contradicts manifest")
                identity = matches[0]["submission_id"]
                verify_entry(load_request(args.request), matches[0])
            from client import Driver
            d = Driver(args.origin, args.evidence, args.timeout, args.deadline, args.interval, args.max_polls)
            result = d.run(args.request, args.job_id, identity, recover=args.command == "recover")
            print(json.dumps({"outcome": result["outcome"], "job_id": result.get("job_id"),
                              "post_attempts": result["post_attempts"], "evidence": str(d.evidence)}))
            return 0 if result["outcome"] == "DONE" else 2
    except (ValueError, OSError, KeyError, TypeError) as exc:
        print(str(exc), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
