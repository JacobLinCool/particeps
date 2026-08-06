"""Command-line boundary for the offline pipeline."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .catalog import CollectorCatalog
from .errors import AnalysisError
from .inventory import CiphertextInventory
from .pipeline import AnalysisPipeline, load_private_keys
from .sink import ParquetSink
from .sources import BundleSource, LocalBundleSource, S3BundleSource


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="particeps-analysis",
        description="Inventory, authenticate, and materialize Particeps Protocol v1 ciphertext bundles.",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    inventory = commands.add_parser(
        "inventory", help="copy ciphertext into the immutable local cache"
    )
    inventory.add_argument("--workspace", type=Path, required=True)
    inventory.add_argument("--local", type=Path, nargs="+")
    inventory.add_argument("--s3-bucket")
    inventory.add_argument("--s3-prefix", default="")
    inventory.add_argument("--s3-endpoint-url")
    inventory.add_argument("--s3-region")
    inventory.add_argument("--s3-profile")

    materialize = commands.add_parser(
        "materialize",
        help="fully verify inventory and atomically publish typed Parquet",
    )
    materialize.add_argument("--workspace", type=Path, required=True)
    materialize.add_argument("--keys", type=Path, required=True)
    materialize.add_argument("--output", type=Path, required=True)
    materialize.add_argument("--catalog", type=Path, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "inventory":
            sources: list[BundleSource] = []
            if args.local:
                sources.append(LocalBundleSource(args.local))
            if args.s3_bucket:
                sources.append(
                    S3BundleSource(
                        args.s3_bucket,
                        prefix=args.s3_prefix,
                        endpoint_url=args.s3_endpoint_url,
                        region_name=args.s3_region,
                        profile_name=args.s3_profile,
                    )
                )
            elif (
                args.s3_prefix
                or args.s3_endpoint_url
                or args.s3_region
                or args.s3_profile
            ):
                raise AnalysisError("S3 options require --s3-bucket")
            if not sources:
                raise AnalysisError("at least one of --local or --s3-bucket is required")
            objects = CiphertextInventory(args.workspace).ingest(sources)
            print(f"inventoried {len(objects)} ciphertext object(s)")
            return 0
        catalog = CollectorCatalog(args.catalog)
        keys = load_private_keys(args.keys)
        pipeline = AnalysisPipeline(args.workspace, catalog, keys, ParquetSink(catalog))
        output = pipeline.materialize(args.output)
        print(output)
        return 0
    except (AnalysisError, OSError) as error:
        parser.exit(2, f"particeps-analysis: {error}\n")


if __name__ == "__main__":
    sys.exit(main())
