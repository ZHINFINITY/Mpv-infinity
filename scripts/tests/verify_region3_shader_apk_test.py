#!/usr/bin/env python3
"""Exercise the engine-specific MPV/Native APK verification contract."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VERIFIER_PATH = ROOT / "scripts/verify-region3-shader-apk.py"
SPEC = importlib.util.spec_from_file_location("verify_region3_shader_apk", VERIFIER_PATH)
VERIFIER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(VERIFIER)


class Region3ShaderApkVerifierTest(unittest.TestCase):
    def write_apk(self, directory: Path, mpv: bytes, native: bytes) -> Path:
        apk = directory / "test.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr(VERIFIER.MPV_LIBRARY, mpv)
            archive.writestr(VERIFIER.NATIVE_LIBRARY, native)
        return apk

    def test_accepts_fongmi_mpv_option_and_native_region3_shader_separately(self):
        shader = VERIFIER.region3_shader(ROOT)
        with tempfile.TemporaryDirectory() as temp:
            apk = self.write_apk(
                Path(temp),
                b"FongMi\0motion-interpolation\0",
                b"Native\0" + shader + b"\0",
            )
            result = VERIFIER.verify_apk(apk, ROOT)
        self.assertEqual(result["mpv_memc_marker"], "motion-interpolation")
        self.assertEqual(result["native_library"], VERIFIER.NATIVE_LIBRARY)
        self.assertEqual(result["shader_bytes"], len(shader))

    def test_rejects_native_library_missing_exact_region3_shader(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = self.write_apk(Path(temp), b"motion-interpolation", b"no shader")
            with self.assertRaisesRegex(AssertionError, "Native Region3 synthesis shader"):
                VERIFIER.verify_apk(apk, ROOT)

    def test_rejects_mpv_library_missing_fongmi_memc_marker(self):
        shader = VERIFIER.region3_shader(ROOT)
        with tempfile.TemporaryDirectory() as temp:
            apk = self.write_apk(Path(temp), b"legacy interpolation only", shader)
            with self.assertRaisesRegex(AssertionError, "FongMi MEMC option marker"):
                VERIFIER.verify_apk(apk, ROOT)


if __name__ == "__main__":
    unittest.main()
