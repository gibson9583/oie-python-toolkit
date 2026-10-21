#!/usr/bin/env python3
"""Keep the Maven, SDK, browser, catalog and release-tag versions in agreement."""
import argparse
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def release_version(root=ROOT):
    value = ET.parse(root / "pom.xml").findtext("m:version", namespaces=NS)
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", value or ""):
        raise ValueError("Maven version must be a release version")
    return value


def default_plugin():
    return ROOT / "package/target" / f"python-runtime-{release_version()}.zip"


def verify_versions(root=ROOT, environ=None):
    environ = os.environ if environ is None else environ
    version = release_version(root)
    for module in ("shared", "server", "client", "package"):
        parent = ET.parse(root / module / "pom.xml").findtext("m:parent/m:version", namespaces=NS)
        if parent != version:
            raise ValueError(f"{module} Maven parent version must match {version}")
    for name in ("plugin.json", "package.json", "package-lock.json"):
        data = json.loads((root / "package/webadmin" / name).read_text())
        if data["version"] != version or (name == "package-lock.json" and data["packages"][""]["version"] != version):
            raise ValueError(f"{name} version must match {version}")
    for relative, pattern in (
        ("python-sdk/oie_python/__init__.py", r'__version__ = "([^"]+)"'),
        ("server/src/main/java/com/mirth/connect/plugins/python/server/Sdk.java", r'String VERSION = "([^"]+)"'),
    ):
        match = re.search(pattern, (root / relative).read_text())
        if not match or match[1] != version:
            raise ValueError(f"{relative} SDK version must match {version}")
    catalog = json.loads((root / "oie.json").read_text())
    if catalog["version"] != version or catalog["filename"] != "python-runtime-{version}.zip":
        raise ValueError("Catalog version/filename must match the release")
    if catalog["id"] != "python-runtime" or not (root / catalog["storeDocs"]).is_file():
        raise ValueError("Catalog identity/store documentation is invalid")
    supported = ET.parse(root / "pom.xml").findtext("m:properties/m:mirth.compatible.versions", namespaces=NS)
    if supported != "4.6.0, 4.7.0" or catalog["minEngineVersion"] != "4.6.0" or catalog["maxEngineVersion"] != "4.7.0":
        raise ValueError("Engine compatibility declarations disagree")
    if environ.get("GITHUB_REF_TYPE") == "tag" and environ.get("GITHUB_REF_NAME") != "v" + version:
        raise ValueError(f"Release tag must be v{version}")
    return version


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", action="store_true")
    args = parser.parse_args()
    print(release_version() if args.version else "Validated Python Toolkit " + verify_versions())
