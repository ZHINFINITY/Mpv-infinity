/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner report (build 1907): turning off "Animate infinity logo" also froze the pull-to-refresh
 * infinity on every tab. The preference must only reach the home top-bar logo.
 */
class InfinityLogoPreferenceScopeTest {
  private val root = generateSequence(File("").absoluteFile) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz/App.kt").isFile }

  private fun mainSources(): Sequence<File> =
    File(root, "app/src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }

  private val pullRefresh =
    File(root, "app/src/main/java/app/infinity/mpvz/presentation/components/pullrefresh/PullRefreshBox.kt").readText()

  @Test
  fun pullToRefreshAndLoadingIndicatorsDoNotReadThePreference() {
    assertFalse("PullRefreshBox must not read the logo preference", "animateInfinityLogo" in pullRefresh)
    val refreshBody = pullRefresh.substringAfter("fun PullRefreshBox(").substringBefore("fun InfinityLoadingIndicator(")
    assertTrue(
      "the pull-to-refresh mark loops only while refreshing or pulled",
      "val infinityDashOffset = infinityDashOffset(animate = isRefreshing.value || state.distanceFraction > 0f)" in refreshBody,
    )
    assertFalse("hidden refresh indicators must stop the infinite transition", "infinityDashOffset(animate = true)" in refreshBody)
    assertTrue(
      "loading indicators loop unless a caller opts out",
      "fun InfinityLoadingIndicator(modifier: Modifier = Modifier, size: Dp = 56.dp, animate: Boolean = true)" in pullRefresh,
    )
    assertTrue("val dashOffset = infinityDashOffset(animate)" in pullRefresh)
  }

  @Test
  fun onlyTheHomeTopBarLogoFollowsThePreference() {
    val readers = mainSources()
      .filter { "animateInfinityLogo" in it.readText() }
      .map { it.relativeTo(root).invariantSeparatorsPath }
      .toSet()
    assertEquals(
      setOf(
        "app/src/main/java/app/infinity/mpvz/preferences/AppearancePreferences.kt",
        "app/src/main/java/app/infinity/mpvz/ui/preferences/AppearancePreferencesScreen.kt",
        "app/src/main/java/app/infinity/mpvz/ui/browser/components/BrowserTopBar.kt",
      ),
      readers,
    )
    val topBar = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/components/BrowserTopBar.kt").readText()
    assertEquals("exactly one logo receives the preference", 1, Regex("animate = animateInfinityLogo").findAll(topBar).count())
    val homeLogo = topBar.substringAfter("if (title == \"∞\")").substringBefore("animate = animateInfinityLogo")
    assertTrue("the preference is passed to the home ∞ title logo", "InfinityLoadingIndicator(" in homeLogo)
  }

  @Test
  fun otherInfinityIndicatorCallersKeepTheLoopingDefault() {
    mainSources()
      .filter { it.name != "BrowserTopBar.kt" && it.name != "PullRefreshBox.kt" }
      .forEach { file ->
        Regex("InfinityLoadingIndicator\\(([^)]*)\\)").findAll(file.readText()).forEach { call ->
          assertFalse("${file.name} must not stop a loading indicator: ${call.value}", "animate" in call.groupValues[1])
        }
      }
  }
}
