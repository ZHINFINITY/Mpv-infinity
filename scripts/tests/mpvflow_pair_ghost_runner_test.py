#!/usr/bin/env python3
"""Check lavapipe discovery without depending on the host Vulkan installation.

SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
"""
import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "pair_ghost_runner", Path(__file__).with_name("mpvflow_pair_ghost_test.py")
)
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class LavapipeDiscoveryTest(unittest.TestCase):
    """Cover distro-specific ICD names and the required-tooling failure path."""

    def discover(self, icds):
        """Run discovery against a controlled list of installed ICD manifests."""
        def glob(path, pattern):
            if str(path) == "/usr/share/vulkan/icd.d":
                return iter(icds)
            return iter([Path("/usr/lib/libvulkan.so")])

        with patch.object(RUNNER.shutil, "which", return_value="/tool"), \
             patch.object(Path, "is_file", return_value=True), \
             patch.object(Path, "glob", glob):
            return RUNNER.require_vulkan()

    def test_architecture_suffixed_icd_is_returned(self):
        """Accept the architecture-suffixed filename used by some distros."""
        icd = Path("/usr/share/vulkan/icd.d/lvp_icd.x86_64.json")
        self.assertEqual(self.discover([icd]), icd)

    def test_selection_is_deterministic(self):
        """Select the sorted first manifest when multiple ICDs are present."""
        icds = [Path("/usr/share/vulkan/icd.d/lvp_icd.z.json"),
                Path("/usr/share/vulkan/icd.d/lvp_icd.a.json")]
        self.assertEqual(self.discover(icds), sorted(icds)[0])

    def test_missing_lavapipe_fails_explicitly(self):
        """Do not silently substitute a missing hardcoded manifest."""
        with self.assertRaisesRegex(SystemExit, "lavapipe"):
            self.discover([])


if __name__ == "__main__":
    unittest.main()
