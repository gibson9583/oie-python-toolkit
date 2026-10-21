"""Package boundary tests; cryptographic verification is exercised in signing tests."""
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import inspect_plugin as inspector
from release_contract import default_plugin


class PackageInspectionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="toolkit-package-test-")
        self.addCleanup(temporary.cleanup)
        self.bundle = Path(temporary.name) / "oie-python-toolkit-1.0.0.zip"
        # Use the artifact produced by the actual Maven build, not a manufactured engine API.
        self.original = default_plugin().read_bytes()
        self.bundle.write_bytes(self.original)

    def rewrite(self, change):
        with zipfile.ZipFile(io.BytesIO(self.original)) as source, zipfile.ZipFile(self.bundle, "w") as target:
            for entry in source.infolist():
                target.writestr(entry, change(entry.filename, source.read(entry)))

    @staticmethod
    def signature_marker(raw):
        output = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(raw)) as source, zipfile.ZipFile(output, "w") as target:
            for entry in source.infolist():
                target.writestr(entry, source.read(entry))
            target.writestr("META-INF/TEST.SF", "structural fixture; never a valid signature")
        return output.getvalue()

    def test_unsigned_build_and_signed_release_modes_cannot_be_confused(self):
        self.assertFalse(inspector.inspect(self.bundle)["signed"])
        with self.assertRaisesRegex(AssertionError, "Unsigned release library"):
            inspector.inspect(self.bundle, "a" * 64)

    def test_every_release_jar_is_sent_to_cryptographic_verification(self):
        self.rewrite(lambda name, raw: self.signature_marker(raw) if name.endswith(".jar") else raw)
        with self.assertRaisesRegex(AssertionError, "requires --certificate-sha256"):
            inspector.inspect(self.bundle)
        with patch.object(inspector, "verify_signed_jar") as verifier:
            result = inspector.inspect(self.bundle, "a" * 64)
        self.assertTrue(result["signed"])
        self.assertEqual(verifier.call_count, 3)
        self.assertEqual({Path(call.args[1]).name for call in verifier.call_args_list},
                         {f"oie-python-toolkit-{module}-1.0.0.jar" for module in ("shared", "server", "client")})

    def test_failed_signature_verification_aborts_package_inspection(self):
        self.rewrite(lambda name, raw: self.signature_marker(raw) if name.endswith(".jar") else raw)
        with patch.object(inspector, "verify_signed_jar", side_effect=ValueError("untrusted signer")):
            with self.assertRaisesRegex(ValueError, "untrusted signer"):
                inspector.inspect(self.bundle, "a" * 64)

    def test_stale_connector_version_is_rejected(self):
        self.rewrite(lambda name, raw: raw.replace(b"<pluginVersion>1.0.0", b"<pluginVersion>0.1.0")
                     if name.endswith("source.xml") else raw)
        with self.assertRaisesRegex(AssertionError, "Connector version"):
            inspector.inspect(self.bundle)


if __name__ == "__main__":
    unittest.main()
