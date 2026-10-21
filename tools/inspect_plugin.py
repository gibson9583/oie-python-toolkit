#!/usr/bin/env python3
"""Inspect the unsigned, self-contained plugin ZIP without installing it."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

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


def inspect(path):
    result = {"plugin": str(path.resolve()), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
              "signed": False, "hostChangesRequired": False, "libraries": []}
    with zipfile.ZipFile(path) as package:
        names = package.namelist()
        assert len(names) == len(set(names)), "Duplicate archive entries"
        assert all(name.startswith("python-runtime/") and ".." not in Path(name).parts for name in names), "Foreign archive path"
        metadata = ET.fromstring(package.read("python-runtime/plugin.xml"))
        assert metadata.get("path") == "python-runtime" and metadata.findtext("mirthVersion") == "4.6.0, 4.7.0"
        for name in ("source.xml", "destination.xml"):
            assert ET.fromstring(package.read("python-runtime/" + name)).findtext("mirthVersion") == "4.6.0, 4.7.0"
        web = json.loads(package.read("python-runtime/webadmin/plugin.json"))
        assert web["oie"]["apiMin"] == "4.10"
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
                assert not signatures, f"Signed plugin library: {name}"
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
    parser.add_argument("--plugin", type=Path, default=ROOT / "package/target/python-runtime-0.1.0.zip")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = inspect(args.plugin)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("plugin", "sha256", "signed", "hostChangesRequired", "engineVersion", "webApiMin")}, indent=2))


if __name__ == "__main__":
    main()
