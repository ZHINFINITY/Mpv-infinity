#!/usr/bin/env python3
"""Exercise the engine-specific pair-ghost APK verification contract."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VERIFIER_PATH = ROOT / "scripts/verify-pair-ghost-apk.py"
SPEC = importlib.util.spec_from_file_location("verify_pair_ghost_apk", VERIFIER_PATH)
VERIFIER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(VERIFIER)


class PairGhostApkVerifierTest(unittest.TestCase):
    def write_apk(self, directory: Path, mpv: bytes, native: bytes) -> Path:
        apk = directory / "test.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr(VERIFIER.MPV_LIBRARY, mpv)
            archive.writestr(VERIFIER.NATIVE_LIBRARY, native)
        return apk

    def test_accepts_fongmi_memc_in_mpv_and_pair_policy_in_native(self):
        pair_policy = b"\0".join(VERIFIER.PAIR_GHOST_MARKERS)
        with tempfile.TemporaryDirectory() as temp:
            apk = self.write_apk(
                Path(temp),
                b"FongMi\0motion-interpolation\0",
                b"Native\0" + pair_policy + b"\0",
            )
            report = VERIFIER.verify_apk(apk)
        self.assertEqual(report["libraries"][0]["verified_markers"], ["motion-interpolation"])
        self.assertEqual(
            report["libraries"][1]["verified_markers"],
            [marker.decode() for marker in VERIFIER.PAIR_GHOST_MARKERS],
        )

    def test_rejects_native_library_missing_pair_policy_marker(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = self.write_apk(Path(temp), b"motion-interpolation", b"no pair policy")
            with self.assertRaisesRegex(AssertionError, "lacks pair policy evidence"):
                VERIFIER.verify_apk(apk)

    def test_rejects_mpv_library_missing_fongmi_memc_marker(self):
        pair_policy = b"\0".join(VERIFIER.PAIR_GHOST_MARKERS)
        with tempfile.TemporaryDirectory() as temp:
            apk = self.write_apk(Path(temp), b"legacy pair ghost only", pair_policy)
            with self.assertRaisesRegex(AssertionError, "FongMi MEMC option marker"):
                VERIFIER.verify_apk(apk)


if __name__ == "__main__":
    unittest.main()
