/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.repository.ai

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class SpeechSegment(
  val startMs: Long,
  val endMs: Long,
  val text: String,
)

@Serializable
data class SpeechTranscript(
  val text: String,
  val segments: List<SpeechSegment> = emptyList(),
)

interface SpeechToTextClient {
  suspend fun transcribe(
    apiKey: String,
    audioFile: File,
    language: String?,
    model: String? = null,
  ): Result<SpeechTranscript>
}
