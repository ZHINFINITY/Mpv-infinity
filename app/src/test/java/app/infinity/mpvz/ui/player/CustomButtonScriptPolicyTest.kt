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
    assertTrue(viewModel.contains("CustomButtonScriptInstancePolicy.versionToken(generation)"))
    assertTrue(viewModel.contains("mp.get_property_native(version_property) == instance_version"))
    assertTrue(viewModel.contains("mp.get_property_native(versionProperty) === instanceVersion"))
    assertTrue(viewModel.contains("if not is_active_instance() then return end"))
    assertTrue(viewModel.contains("if (!isActiveInstance()) return;"))
  }
}
