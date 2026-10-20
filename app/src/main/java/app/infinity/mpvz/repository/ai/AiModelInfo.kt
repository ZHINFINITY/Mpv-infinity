/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.repository.ai

import kotlinx.serialization.Serializable

@Serializable
data class AiModelInfo(
  val id: String,
  val displayName: String,
  val isFree: Boolean = false,
)
