#!/usr/bin/env python3
"""Build an unsigned plugin against an existing, unmodified OIE installation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
VERSION = "4.6.0-plugin-api"
ARTIFACTS = {
    "donkey-model": "server-lib/donkey/donkey-model.jar",
    "donkey-server": "server-lib/donkey/donkey-server.jar",
    "mirth-client-core": "server-lib/mirth-client-core.jar",
    "mirth-server": "server-lib/mirth-server.jar",
    "mirth-crypto": "server-lib/mirth-crypto.jar",
    "mirth-client": "client-lib/mirth-client.jar",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine-install", type=Path, required=True)
    parser.add_argument("--stage-only", action="store_true")
    args = parser.parse_args()
    engine = args.engine_install.resolve(strict=True)
    inputs = []
    for artifact, relative in ARTIFACTS.items():
        source = engine / relative
        if not source.is_file():
            parser.error(f"Installed engine library is missing: {relative}")
        with source.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        subprocess.run(["mvn", "-q", "install:install-file", f"-Dfile={source}",
                        "-DgroupId=com.mirth.connect", f"-DartifactId={artifact}",
                        f"-Dversion={VERSION}", "-Dpackaging=jar"], cwd=ROOT, check=True)
        inputs.append({"artifact": artifact, "path": str(source), "sha256": digest})
    evidence = ROOT / "target" / "installed-api-inputs.json"
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps({"coordinateVersion": VERSION, "engineModified": False,
                                    "pluginSigned": False, "inputs": inputs}, indent=2) + "\n")
    if not args.stage_only:
        subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "python-sdk/tests", "-v"],
                       cwd=ROOT, check=True, env=os.environ | {"PYTHONPATH": str(ROOT / "python-sdk")})
        subprocess.run(["mvn", "verify"], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
