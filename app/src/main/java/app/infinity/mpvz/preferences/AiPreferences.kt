/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.preferences

import app.infinity.mpvz.preferences.preference.Preference
import app.infinity.mpvz.preferences.preference.PreferenceStore
import app.infinity.mpvz.preferences.preference.getEnum

enum class AiProvider(
  val displayName: String,
) {
  OPENCODE("OpenCode"),
  GROQ("Groq"),
  OPENAI("OpenAI"),
  ANTHROPIC("Anthropic"),
  OPENROUTER("OpenRouter"),
  TOGETHER("Together"),
}

class AiPreferences(
  preferenceStore: PreferenceStore,
) {
  val enabled = preferenceStore.getBoolean("ai_enabled", false)

  val provider = preferenceStore.getEnum("ai_provider", AiProvider.OPENCODE)

  val openCodeApiKey = preferenceStore.getString("ai_opencode_api_key", "")
  val groqApiKey = preferenceStore.getString("ai_groq_api_key", "")
  val openaiApiKey = preferenceStore.getString("ai_openai_api_key", "")
  val anthropicApiKey = preferenceStore.getString("ai_anthropic_api_key", "")
  val openrouterApiKey = preferenceStore.getString("ai_openrouter_api_key", "")
  val togetherApiKey = preferenceStore.getString("ai_together_api_key", "")

  val selectedModel = preferenceStore.getString("ai_selected_model", "")

  val availableModels = preferenceStore.getString("ai_available_models", "[]")

  private val openCodeSelectedModel = preferenceStore.getString("ai_selected_model_opencode", "")
  private val groqSelectedModel = preferenceStore.getString("ai_selected_model_groq", "")
  private val openAiSelectedModel = preferenceStore.getString("ai_selected_model_openai", "")
  private val anthropicSelectedModel = preferenceStore.getString("ai_selected_model_anthropic", "")
  private val openRouterSelectedModel = preferenceStore.getString("ai_selected_model_openrouter", "")
  private val togetherSelectedModel = preferenceStore.getString("ai_selected_model_together", "")

  private val openCodeAvailableModels = preferenceStore.getString("ai_available_models_opencode", "[]")
  private val groqAvailableModels = preferenceStore.getString("ai_available_models_groq", "[]")
  private val openAiAvailableModels = preferenceStore.getString("ai_available_models_openai", "[]")
  private val anthropicAvailableModels = preferenceStore.getString("ai_available_models_anthropic", "[]")
  private val openRouterAvailableModels = preferenceStore.getString("ai_available_models_openrouter", "[]")
  private val togetherAvailableModels = preferenceStore.getString("ai_available_models_together", "[]")

  val subtitleGenerationOutputFormat = preferenceStore.getString("ai_subtitle_generation_output_format", "srt")

  // Speech-to-text (real-time subs + batch generation)
  val sttProvider = preferenceStore.getEnum("ai_stt_provider", AiProvider.GROQ)
  val sttModel = preferenceStore.getString("ai_stt_model", "")
  val sttAvailableModels = preferenceStore.getString("ai_stt_available_models", "[]")
  val sttLanguage = preferenceStore.getString("ai_stt_language", "")

  // Auto-translate target languages (comma-separated codes: "en,es,fr")
  val autoTranslateLanguages = preferenceStore.getString("ai_auto_translate_languages", "")

  // Real-time embedded soft-subtitle translation. Google Translate is the no-key default;
  // optional endpoints support OpenAI-compatible, DeepL, and Gemini-compatible services.
  val embeddedSubtitleTranslationProvider =
    preferenceStore.getString("ai_embedded_subtitle_translation_provider", "Google Translate")
  val embeddedSubtitleTranslationEndpoint =
    preferenceStore.getString(
      "ai_embedded_subtitle_translation_endpoint",
      "https://translate.googleapis.com/translate_a/single",
    )
  val embeddedSubtitleTranslationApiKey =
    preferenceStore.getString("ai_embedded_subtitle_translation_api_key", "")

  val customPromptEnabled = preferenceStore.getBoolean("ai_custom_prompt_enabled", false)
  val customPrompt = preferenceStore.getString("ai_custom_prompt", "")
  val customRenamePrompt = preferenceStore.getString("ai_custom_rename_prompt", "")
  val customSubtitleTranslationPrompt = preferenceStore.getString("ai_custom_subtitle_translation_prompt", "")
  val customSubtitleFormatPrompt = preferenceStore.getString("ai_custom_subtitle_format_prompt", "")

  val renameWithAi = preferenceStore.getBoolean("ai_rename_enabled", true)
  val subtitleFormatWithAi = preferenceStore.getBoolean("ai_subtitle_format_enabled", true)
  val subtitleTranslationEnabled = preferenceStore.getBoolean("ai_subtitle_translation_enabled", false)

  // Real-time subtitle generation (speech-to-text while playing)
  val realtimeSubsEnabled = preferenceStore.getBoolean("ai_realtime_subs_enabled", true)
  val subtitleTranslationFirstTime = preferenceStore.getBoolean("ai_subtitle_translation_first_time", true)

  val lastVerified = preferenceStore.getLong("ai_last_verified", 0L)

  init {
    val currentProvider = provider.get()
    val providerModel = selectedModelFor(currentProvider)
    if (providerModel.get().isBlank() && selectedModel.get().isNotBlank()) {
      providerModel.set(selectedModel.get())
    }
    val providerModels = availableModelsFor(currentProvider)
    if (providerModels.get() == "[]" && availableModels.get() != "[]") {
      providerModels.set(availableModels.get())
    }
  }

  fun selectedModelFor(provider: AiProvider): Preference<String> =
    when (provider) {
      AiProvider.OPENCODE -> openCodeSelectedModel
      AiProvider.GROQ -> groqSelectedModel
      AiProvider.OPENAI -> openAiSelectedModel
      AiProvider.ANTHROPIC -> anthropicSelectedModel
      AiProvider.OPENROUTER -> openRouterSelectedModel
      AiProvider.TOGETHER -> togetherSelectedModel
    }

  fun availableModelsFor(provider: AiProvider): Preference<String> =
    when (provider) {
      AiProvider.OPENCODE -> openCodeAvailableModels
      AiProvider.GROQ -> groqAvailableModels
      AiProvider.OPENAI -> openAiAvailableModels
      AiProvider.ANTHROPIC -> anthropicAvailableModels
      AiProvider.OPENROUTER -> openRouterAvailableModels
      AiProvider.TOGETHER -> togetherAvailableModels
    }
}
