#!/usr/bin/env python3
"""Inspect a self-contained plugin ZIP; verify release signatures when requested."""
import argparse
import hashlib
import io
import importlib.util
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
import tempfile

from release_contract import default_plugin, verify_versions

ROOT = Path(__file__).resolve().parents[1]
MODULES = {"shared", "server", "client"}
CONNECTOR_PANELS = {"source.xml": "com.mirth.connect.plugins.python.client.PythonReceiverPanel",
                    "destination.xml": "com.mirth.connect.plugins.python.client.PythonDispatcherPanel"}
CLIENT_CLASSES = {"com/mirth/connect/plugins/python/client/" + name + ".class"
                  for name in ("PythonConnectorPanel", "PythonReceiverPanel", "PythonDispatcherPanel")}
OWN_PACKAGES = ("com/mirth/connect/plugins/python/", "com/mirth/connect/connectors/python/")
HOST_ONLY_TYPES = (b"com/mirth/connect/plugins/ChannelDeploymentPlugin", b"com/mirth/connect/plugins/DeploymentContext",
                   b"com/mirth/connect/donkey/server/channel/ChannelExecutionContext",
                   b"getProtectedPluginProperties", b"compareAndSetPluginProperties")


def verify_signed_jar(raw, name, certificate_sha256):
    spec = importlib.util.spec_from_file_location("toolkit_signing", ROOT / ".github/signing/sign_release.py")
    signing = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(signing)
    expected = signing.fingerprint(certificate_sha256)
    with tempfile.TemporaryDirectory(prefix="python-toolkit-verify-") as temporary:
        jar = Path(temporary) / Path(name).name
        jar.write_bytes(raw)
        signing.verify_jar(jar, expected)


def inspect(path, certificate_sha256=None):
    version = verify_versions()
    result = {"plugin": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
              "signed": bool(certificate_sha256), "hostChangesRequired": False, "libraries": []}
    result["certificateSha256"] = certificate_sha256.replace(":", "").strip().lower() if certificate_sha256 else None
    with zipfile.ZipFile(path) as package:
        names = package.namelist()
        assert len(names) == len(set(names)), "Duplicate archive entries"
        assert all(name.startswith("python-runtime/") and ".." not in Path(name).parts for name in names), "Foreign archive path"
        metadata = ET.fromstring(package.read("python-runtime/plugin.xml"))
        assert metadata.get("path") == "python-runtime" and metadata.findtext("mirthVersion") == "4.6.0, 4.7.0"
        assert metadata.findtext("pluginVersion") == version, "Plugin version differs from source"
        for name in ("source.xml", "destination.xml"):
            connector = ET.fromstring(package.read("python-runtime/" + name))
            assert connector.findtext("mirthVersion") == "4.6.0, 4.7.0"
            assert connector.findtext("pluginVersion") == version, "Connector version differs from source"
        web = json.loads(package.read("python-runtime/webadmin/plugin.json"))
        assert web["oie"]["apiMin"] == "4.10"
        assert web["version"] == version, "Browser plugin version differs from source"
        assert b"contextInputs" not in package.read("python-runtime/webadmin/web/plugin.js"), "Removed selector contract remains in browser bundle"
        language_path = "python-runtime/webadmin/web/python-language.json"
        language = json.loads(package.read(language_path))
        assert language["schemaVersion"] == 1 and language["tokensProvider"]["tokenizer"]
        assert "Microsoft Corporation" in package.read("python-runtime/webadmin/web/python-language.LICENSE.txt").decode()
        result["editorLanguage"] = {"path": language_path, "sha256": hashlib.sha256(package.read(language_path)).hexdigest(),
                                   "monacoVersion": language["monacoVersion"], "sourceSha256": language["sourceSha256"]}
        assert metadata.find("clientClasses") is None, "Swing plugin registrations must not be packaged"
        for name, panel in CONNECTOR_PANELS.items():
            connector = ET.fromstring(package.read("python-runtime/" + name))
            assert connector.findtext("clientClassName") == panel, "Connector must register only its Swing placeholder panel"
        assert "python-runtime/operator.properties.example" in names
        libraries = [name for name in names if name.endswith(".jar")]
        expected = {f"python-runtime/libs/oie-python-toolkit-{module}-{metadata.findtext('pluginVersion')}.jar" for module in MODULES}
        assert set(libraries) == expected, "Package must contain only the shared, server and client plugin libraries"
        for name in sorted(libraries):
            raw = package.read(name)
            with zipfile.ZipFile(io.BytesIO(raw)) as library:
                entries = library.namelist()
                signatures = [entry for entry in entries if entry.upper().startswith("META-INF/")
                              and (entry.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))
                                   or Path(entry).name.upper().startswith("SIG-"))]
                if certificate_sha256:
                    assert signatures, f"Unsigned release library: {name}"
                    verify_signed_jar(raw, name, certificate_sha256)
                else:
                    assert not signatures, f"Signed plugin library requires --certificate-sha256: {name}"
                classes = [entry for entry in entries if entry.endswith(".class")]
                assert not any("ContextInput" in entry or "EngineInputKeys" in entry for entry in classes), "Removed catalog classes remain in package"
                assert all(entry.startswith(OWN_PACKAGES) for entry in classes), f"Bundled host/third-party classes: {name}"
                client = name.endswith("-client-" + metadata.findtext("pluginVersion") + ".jar")
                assert not client or set(classes) == CLIENT_CLASSES, "Client library must contain only the connector placeholder panels"
                for entry in classes:
                    assert b"contextInputs" not in library.read(entry), f"Removed selector field remains in {entry}"
                    assert not any(token in library.read(entry) for token in HOST_ONLY_TYPES), f"Host patch dependency: {entry}"
                    assert client or b"javax/swing/" not in library.read(entry), f"Swing dependency: {entry}"
                result["libraries"].append({"path": name, "sha256": hashlib.sha256(raw).hexdigest(),
                                             "bytes": len(raw), "classes": len(classes), "signatureEntries": signatures})
        result["engineVersion"] = metadata.findtext("mirthVersion")
        result["webApiMin"] = web["oie"]["apiMin"]
        result["entries"] = names
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin", type=Path, default=default_plugin())
    parser.add_argument("--certificate-sha256", help="Require every JAR to be signed by this certificate, with trusted timestamps")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = inspect(args.plugin, args.certificate_sha256)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("plugin", "sha256", "signed", "hostChangesRequired", "engineVersion", "webApiMin")}, indent=2))


if __name__ == "__main__":
    main()
