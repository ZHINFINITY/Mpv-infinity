/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.infinity.mpvz.preferences.AdvancedPreferences
import app.infinity.mpvz.preferences.MpvConfigOverride
import app.infinity.mpvz.preferences.MpvConfigOverridePolicy
import app.infinity.mpvz.preferences.preference.collectAsState
import org.koin.compose.koinInject

@Composable
fun MpvConfigOverride.isOwnedByMpvConf(): Boolean {
  val selectedOptions = currentMpvConfigOverrideOptions()
  return optionNames.any(selectedOptions::contains)
}

@Composable
fun isMpvOptionOwnedByConfig(optionName: String): Boolean = optionName in currentMpvConfigOverrideOptions()

@Composable
fun isAnyMpvOptionOwnedByConfig(optionNames: Set<String>): Boolean {
  val selectedOptions = currentMpvConfigOverrideOptions()
  return optionNames.any(selectedOptions::contains)
}

@Composable
fun currentMpvConfigOverrideOptions(): Set<String> {
  val preferences = koinInject<AdvancedPreferences>()
  val storedValues by preferences.mpvConfOverrides.collectAsState()
  return remember(storedValues) {
    MpvConfigOverridePolicy.effectiveOptionNames(MpvConfigOverride.resolveOptionNames(storedValues))
  }
}