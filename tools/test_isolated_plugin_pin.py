"""Focused regressions for concurrent publication of shared validation inputs."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tempfile
import threading
import unittest

from run_code_template_isolated import pin_plugin


class PluginPinTest(unittest.TestCase):
    def test_concurrent_identical_inputs_publish_complete_pin(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / 'digest' / 'plugin.zip'
            content = b'plugin-content' * 100000
            ready = threading.Barrier(8)

            def publish(_):
                ready.wait()
                pin_plugin(target, content)
                self.assertEqual(target.read_bytes(), content)

            with ThreadPoolExecutor(max_workers=8) as executor:
                list(executor.map(publish, range(8)))
            self.assertEqual(list(target.parent.iterdir()), [target])

    def test_conflicting_pin_is_preserved_and_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / 'plugin.zip'
            pin_plugin(target, b'original')
            with self.assertRaisesRegex(AssertionError, 'Pinned plugin content mismatch'):
                pin_plugin(target, b'conflicting')
            self.assertEqual(target.read_bytes(), b'original')
            self.assertEqual(list(target.parent.iterdir()), [target])


if __name__ == '__main__':
    unittest.main()
