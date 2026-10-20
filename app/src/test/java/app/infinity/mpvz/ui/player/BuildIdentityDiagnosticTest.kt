package app.infinity.mpvz.ui.player

import app.infinity.mpvz.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildIdentityDiagnosticTest {
  @Test
  fun startupLineIncludesFullShaAndDeviceBuildIdentity() {
    val sha = BuildConfig.GIT_SHA
    assertTrue("Expected a full 40-character Git SHA, got: $sha", sha.matches(Regex("[0-9a-f]{40}")))
    val fingerprint = "POCO/onyx_in/onyx:16/BP2A.250605.031.A3/OS3.0.303.0.WOLINXM:user/release-keys"

    val line = BuildIdentityDiagnostic.startupLine(
      gitSha = sha,
      versionName = BuildConfig.VERSION_NAME,
      versionCode = BuildConfig.VERSION_CODE,
      deviceBrand = "POCO",
      deviceModel = "25053PC47I (onyx)",
      deviceProduct = "onyx_in",
      deviceCodeName = "onyx",
      androidRelease = "16",
      androidSdk = 36,
      buildFingerprint = fingerprint,
    )
    assertTrue(line.startsWith("build_identity build_sha=$sha "))
    assertTrue(line.contains("device_brand=POCO"))
    assertTrue(line.contains("device_model=25053PC47I_(onyx)"))
    assertTrue(line.contains("android_release=16 android_sdk=36"))
    assertTrue(line.contains("build_fingerprint=$fingerprint"))
  }

  @Test
  fun summaryShaFieldIsMachineReadable() {
    assertEquals("build_sha=0123456789abcdef", BuildIdentityDiagnostic.shaField("0123456789abcdef"))
  }
}
