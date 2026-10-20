/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.repository.ai

data class AiGenerationOptions(
  val maxTokens: Int = 200,
  val temperature: Double = 0.3,
)

data class AiSource(
  val url: String,
  val title: String? = null,
)

data class AiGeneratedContent(
  val text: String,
  val reasoning: String? = null,
  val sources: List<AiSource> = emptyList(),
)

interface AiClient {
  suspend fun fetchModels(apiKey: String): Result<List<AiModelInfo>>

  suspend fun verifyKey(apiKey: String): Result<String>

  suspend fun generateContent(
    apiKey: String,
    model: String,
    instruction: String,
    userInput: String,
    options: AiGenerationOptions = AiGenerationOptions(),
  ): Result<AiGeneratedContent>
}
