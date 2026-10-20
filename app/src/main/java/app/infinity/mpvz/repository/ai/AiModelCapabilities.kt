/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.repository.ai

internal object AiModelCapabilities {
  fun isTextGenerationModel(id: String): Boolean {
    val value = id.lowercase()
    return listOf(
      "whisper",
      "transcribe",
      "tts",
      "speech",
      "embedding",
      "moderation",
      "dall-e",
      "gpt-image",
      "realtime",
      "audio-preview",
      "guard",
      "safety",
    ).none(value::contains)
  }
}
