/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.anime4k

import app.infinity.mpvz.domain.anime4k.Anime4KManager

data class Anime4KUiState(
  val isEnabled: Boolean = false,
  val selectedMode: String = Anime4KManager.Mode.OFF.name,
  val usesGpuNext: Boolean = false,
  val usesVulkan: Boolean = false,
  val enableIn4k: Boolean = false,
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
) {
  val isHighResolution: Boolean
    get() = videoWidth >= 3840 || videoHeight >= 2160

  val allowHighRes: Boolean
    get() = enableIn4k || !isHighResolution

  val isAvailable: Boolean
    get() = isEnabled && (!usesGpuNext || usesVulkan)
}
