/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level tripwires for the approved 0dd2a9cd browser rollback. */
class ReferenceUiRollbackContractTest {
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private fun read(path: String) = File(root, path).readText()
  private fun sha(value: String) = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

  @Test fun originalFolderCardSourceFingerprintIsPreserved() {
    assertEquals("18760196bbe09db6601592650ce9c243f8a8ff6ddba80e9e2bc0b4bab8fdb563",
      sha(read("app/src/main/java/app/infinity/mpvz/ui/browser/cards/FolderCard.kt")))
  }

  @Test fun preservedPlayerSourcesKeepBaseline7eFingerprints() {
    val expected = mapOf(
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt" to "e94a3ddb5a4e1b9d48964292258dd2fc5174a024b153d5e0e6d4c22712916529",
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/Seekbar.kt" to "b0d7ca11fdd2729871e052a0f6ea3b2cba64fb3e29039e5d3c42a7acf4d426c9",
      "app/src/main/java/app/infinity/mpvz/ui/player/controls/components/GlassControls.kt" to "c5266167f59009db21006a0b0cb68bea1b144350f8046f77cc6cc216c4447ff3",
      "app/src/main/java/app/infinity/mpvz/presentation/components/PlayerSheet.kt" to "fdcf88d13735e1876059f0e7bdcc41088ecedd06d58fffde0283c9e32967e96a",
      "app/src/main/java/app/infinity/mpvz/presentation/components/PlayerGlassOverlays.kt" to "82a115d195711b391235b5f32a4d7b2bac75e494aef0ff22be55251f9307b54f",
      "app/src/main/java/app/infinity/mpvz/ui/theme/LiquidGlassBackdrop.kt" to "f10a7bb20ca6e17e57f8500f5857cae1fc6d9ecf9f30c67512b8925ce2d9b7f3",
    )
    expected.forEach { (path, digest) -> assertEquals(path, digest, sha(read(path))) }
  }

  @Test fun expressiveNavigationBodyAndBrowserLensMatchReference() {
    val navigation = read("app/src/main/java/app/infinity/mpvz/ui/browser/MainScreen.kt")
    val start = navigation.indexOf("private fun ExpressivePillNavigationBar(")
    require(start >= 0)
    val open = navigation.indexOf('{', start)
    var depth = 0
    var end = open
    while (end < navigation.length) {
      if (navigation[end] == '{') depth++
      if (navigation[end] == '}') { depth--; if (depth == 0) { end++; break } }
      end++
    }
    assertEquals("af3ac184bf4593d62f64a2339843a7bed000174672caacf68ad06d10d32d8f95", sha(navigation.substring(start, end)))
    val scene = read("app/src/main/java/app/infinity/mpvz/ui/theme/BrowserGlassScene.kt")
    val lensStart = scene.indexOf("private const val BROWSER_LENS")
    val q = scene.indexOf("\"\"\"", lensStart)
    val lensEnd = scene.indexOf("\"\"\"", q + 3) + 3
    assertEquals("5c4a067ebbd5aabfb2239fd33f3bcb1ead3f03532bb04287499462ac96e8bb1e", sha(scene.substring(lensStart, lensEnd)))
    assertTrue(scene.contains("private const val BROWSER_LATEST_LENS"))
  }
}
