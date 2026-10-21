#!/usr/bin/env python3
"""Fetch checksum-pinned public OIE inputs; optionally stage only its API JARs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

from build_with_engine import ARTIFACTS, ROOT


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fetch(version, output):
    entry = json.loads((ROOT / "ci/engines.json").read_text())[version]
    output.parent.mkdir(parents=True, exist_ok=True)
    if not output.exists():
        with tempfile.TemporaryDirectory(prefix="python-toolkit-download-", dir=output.parent) as temporary:
            partial = Path(temporary) / "engine.tar.gz"
            with urllib.request.urlopen(entry["url"], timeout=120) as response, partial.open("wb") as stream:
                shutil.copyfileobj(response, stream)
            if digest(partial) != entry["sha256"]:
                raise ValueError("Stock engine download SHA-256 mismatch")
            # Publish without replacing an existing shared archive.
            try:
                os.link(partial, output)
            except FileExistsError:
                pass
    if digest(output) != entry["sha256"]:
        raise ValueError("Stock engine input SHA-256 mismatch")
    return entry


def stage_api(archive):
    # This installation is never runnable: extract only the six compile inputs.
    with tempfile.TemporaryDirectory(prefix="python-toolkit-api-") as temporary:
        engine = Path(temporary) / "oie"
        with tarfile.open(archive) as source:
            for relative in ARTIFACTS.values():
                member = source.getmember("oie/" + relative)
                if not member.isfile():
                    raise ValueError("Expected a regular engine API JAR: " + relative)
                target = engine / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as stream, target.open("wb") as output:
                    shutil.copyfileobj(stream, output)
        subprocess.run([sys.executable, str(ROOT / "tools/build_with_engine.py"),
                        "--engine-install", str(engine), "--stage-only"], cwd=ROOT, check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, choices=("4.6.0", "4.7.0"))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--stage-api", action="store_true")
    args = parser.parse_args()
    if args.stage_api and args.version != "4.6.0":
        parser.error("Compile inputs must use the 4.6.0 API baseline")
    entry = fetch(args.version, args.output)
    report = ROOT / "target" / f"engine-input-{args.version}.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(dict(entry, version=args.version, archive=str(args.output),
                                     engineStarted=False), indent=2) + "\n")
    if args.stage_api:
        stage_api(args.output)
