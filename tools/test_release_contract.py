"""Release version drift and public-input failure regressions; no external services."""
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import ci_engine
import release_contract as contract


class ReleaseContractTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="toolkit-release-contract-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        paths = ["pom.xml", "oie.json", "docs/store.md", "python-sdk/oie_python/__init__.py",
                 "server/src/main/java/com/mirth/connect/plugins/python/server/Sdk.java"]
        paths += [module + "/pom.xml" for module in ("shared", "server", "client", "package")]
        paths += ["package/webadmin/" + name for name in ("plugin.json", "package.json", "package-lock.json")]
        for name in paths:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(contract.ROOT / name, target)

    def test_tag_must_match_all_source_versions(self):
        self.assertEqual(contract.verify_versions(self.root, {"GITHUB_REF_TYPE": "tag", "GITHUB_REF_NAME": "v1.0.0"}), "1.0.0")
        with self.assertRaisesRegex(ValueError, "Release tag must be"):
            contract.verify_versions(self.root, {"GITHUB_REF_TYPE": "tag", "GITHUB_REF_NAME": "v0.1.0"})

    def test_browser_lockfile_root_drift_fails(self):
        path = self.root / "package/webadmin/package-lock.json"
        data = json.loads(path.read_text())
        data["packages"][""]["version"] = "0.1.0"
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "package-lock.json"):
            contract.verify_versions(self.root, {})

    def test_sdk_handshake_version_drift_fails(self):
        path = self.root / "python-sdk/oie_python/__init__.py"
        path.write_text(path.read_text().replace('__version__ = "1.0.0"', '__version__ = "0.1.0"'))
        with self.assertRaisesRegex(ValueError, "SDK version"):
            contract.verify_versions(self.root, {})

    def test_parent_and_catalog_compatibility_drift_fail(self):
        path = self.root / "client/pom.xml"
        original = path.read_text()
        path.write_text(original.replace("1.0.0", "0.1.0"))
        with self.assertRaisesRegex(ValueError, "parent version"):
            contract.verify_versions(self.root, {})
        path.write_text(original)
        path = self.root / "oie.json"
        data = json.loads(path.read_text())
        data["maxEngineVersion"] = None
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "Engine compatibility"):
            contract.verify_versions(self.root, {})


class EngineInputTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="toolkit-input-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "ci").mkdir()
        import hashlib
        self.data = b"immutable archive fixture"
        self.entry = {"url": "https://example.invalid/engine", "sha256": hashlib.sha256(self.data).hexdigest()}
        (self.root / "ci/engines.json").write_text(json.dumps({"4.6.0": self.entry}))
        self.output = self.root / "engine.tar.gz"
        self.patch = patch.object(ci_engine, "ROOT", self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def test_verified_input_is_reused_without_network_or_replacement(self):
        with patch.object(ci_engine.urllib.request, "urlopen", return_value=io.BytesIO(self.data)) as request:
            ci_engine.fetch("4.6.0", self.output)
            ci_engine.fetch("4.6.0", self.output)
        self.assertEqual(request.call_count, 1)
        self.assertEqual(self.output.read_bytes(), self.data)

    def test_bad_download_is_never_published(self):
        with patch.object(ci_engine.urllib.request, "urlopen", return_value=io.BytesIO(b"bad")):
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                ci_engine.fetch("4.6.0", self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob("python-toolkit-download-*")), [])

    def test_existing_wrong_input_is_preserved_and_rejected(self):
        self.output.write_bytes(b"other input")
        with patch.object(ci_engine.urllib.request, "urlopen") as request:
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                ci_engine.fetch("4.6.0", self.output)
        request.assert_not_called()
        self.assertEqual(self.output.read_bytes(), b"other input")

    def test_network_failure_does_not_leave_a_partial_input(self):
        with patch.object(ci_engine.urllib.request, "urlopen", side_effect=OSError("offline")):
            with self.assertRaises(OSError):
                ci_engine.fetch("4.6.0", self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob("python-toolkit-download-*")), [])


if __name__ == "__main__":
    unittest.main()
