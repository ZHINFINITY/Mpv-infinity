/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomButtonScriptPolicyTest {
  private fun synchronizedBodies(source: String): List<String> {
    val marker = "synchronized(customButtonScriptsLock) {"
    val bodies = mutableListOf<String>()
    var searchFrom = 0
    while (true) {
      val markerStart = source.indexOf(marker, searchFrom)
      if (markerStart < 0) return bodies
      var index = markerStart + marker.length
      var depth = 1
      var inString = false
      var inChar = false
      var escaped = false
      while (index < source.length && depth > 0) {
        val character = source[index]
        if (escaped) {
          escaped = false
        } else if ((inString || inChar) && character == '\\') {
          escaped = true
        } else if (!inChar && character == '"') {
          inString = !inString
        } else if (!inString && character == '\'') {
          inChar = !inChar
        } else if (!inString && !inChar && character == '{') {
          depth++
        } else if (!inString && !inChar && character == '}') {
          depth--
        }
        index++
      }
      check(depth == 0) { "Unterminated synchronized(customButtonScriptsLock) block" }
      bodies += source.substring(markerStart + marker.length, index - 1)
      searchFrom = index
    }
  }

  @Test
  fun cleanupMatchesOnlyKnownTargetsAndGeneratedNames() {
    val targets = setOf("custombuttons.lua", "custombuttons.js")

    assertTrue(CustomButtonScriptFilePolicy.isManaged("custombuttons.lua", targets))
    assertTrue(
      CustomButtonScriptFilePolicy.isManaged(
        "custombuttons-lua-7-550e8400-e29b-41d4-a716-446655440000.lua",
        targets,
      ),
    )
    assertTrue(
      CustomButtonScriptFilePolicy.isManaged(
        "custombuttons-js-8-550e8400-e29b-41d4-a716-446655440000.js.tmp",
        targets,
      ),
    )
    assertFalse(CustomButtonScriptFilePolicy.isManaged("custombuttons-tools.lua", targets))
    assertFalse(CustomButtonScriptFilePolicy.isManaged("custombuttons-lua-7-not-a-uuid.lua", targets))
  }

  @Test
  fun returningToSameButtonContentGetsANewLuaAndJavaScriptInstanceToken() {
    val firstA = CustomButtonScriptInstancePolicy.versionToken(10L)
    val versionB = CustomButtonScriptInstancePolicy.versionToken(11L)
    val secondA = CustomButtonScriptInstancePolicy.versionToken(12L)
    val recreatedSetup = CustomButtonScriptInstancePolicy.versionToken(10L)

    assertNotEquals(firstA, versionB)
    assertNotEquals(firstA, secondA)
    assertNotEquals(versionB, secondA)
    assertNotEquals(firstA, recreatedSetup)

    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val viewModel = File(root, "app/infinity/mpvz/ui/player/PlayerViewModel.kt").readText()
    val setup = viewModel.substringAfter("private fun setupCustomButtons() {")
      .substringBefore("private fun isCurrentCustomButtonSetup")
    val setupLocks = synchronizedBodies(setup)
    val generationLock = setupLocks.first { it.contains("customButtonsSetupComplete = false") }
    assertFalse(generationLock.contains("val jsonString"))
    val completionLock = setupLocks.first { it.contains("customButtonsSetupComplete = true") }
    assertTrue(completionLock.contains("if (!isCurrentCustomButtonSetup(generation)) throw CancellationException"))
    assertFalse(completionLock.contains("catch (cancelled: CancellationException)"))
    assertTrue(viewModel.contains("CustomButtonScriptInstancePolicy.versionToken(generation)"))
    assertTrue(viewModel.contains("mp.get_property_native(version_property) == instance_version"))
    assertTrue(viewModel.contains("mp.get_property_native(versionProperty) === instanceVersion"))
    assertTrue(viewModel.contains("if not is_active_instance() then return end"))
    assertTrue(viewModel.contains("if (!isActiveInstance()) return;"))
  }
}
