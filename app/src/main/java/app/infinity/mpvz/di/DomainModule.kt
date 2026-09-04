/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.di

import app.infinity.mpvz.domain.anime4k.Anime4KManager
import app.infinity.mpvz.domain.hdr.HdrToysManager
import app.infinity.mpvz.domain.hdr.MpvShaderRuntime
import app.infinity.mpvz.domain.torrent.TorrentStreamingEngine
import app.infinity.mpvz.network.AndroidCookieJar
import app.infinity.mpvz.network.SharedHttpClient
import app.infinity.mpvz.preferences.AiPreferences
import app.infinity.mpvz.repository.IntroDbRepository
import app.infinity.mpvz.repository.ai.AiClient
import app.infinity.mpvz.repository.ai.AiService
import app.infinity.mpvz.repository.ai.AnthropicClient
import app.infinity.mpvz.repository.ai.EmbeddedSubtitleTranslator
import app.infinity.mpvz.repository.ai.GroqClient
import app.infinity.mpvz.repository.ai.GroqSpeechClient
import app.infinity.mpvz.repository.ai.OpenAiClient
import app.infinity.mpvz.repository.ai.OpenCodeClient
import app.infinity.mpvz.repository.ai.OpenRouterClient
import app.infinity.mpvz.repository.ai.OpenRouterSpeechClient
import app.infinity.mpvz.repository.ai.RealtimeSubtitleService
import app.infinity.mpvz.repository.ai.SubtitleGenerationService
import app.infinity.mpvz.repository.ai.TogetherClient
import app.infinity.mpvz.repository.subtitle.OnlineSubtitleFileStore
import app.infinity.mpvz.repository.subtitle.OnlineSubtitleOrchestrator
import app.infinity.mpvz.repository.subtitlehub.MpvRxSubtitleHubRepository
import app.infinity.mpvz.repository.wyzie.WyzieSearchRepository
import app.infinity.mpvz.ui.player.PlaybackSessionShaderRuntime
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val domainModule =
  module {
    single { AndroidCookieJar() }
    single<OkHttpClient> {
      SharedHttpClient.derive {
        connectTimeout(30, TimeUnit.SECONDS)
        cookieJar(get<AndroidCookieJar>())
      }
    }
    single { Anime4KManager(androidContext()) }
    single<MpvShaderRuntime> { PlaybackSessionShaderRuntime }
    single { HdrToysManager(androidContext(), get()) }
    single { OnlineSubtitleFileStore(androidContext(), get()) }
    single { WyzieSearchRepository(androidContext(), get(), get(), get(), get()) }
    single { MpvRxSubtitleHubRepository(get(), get(), get(), get()) }
    single { OnlineSubtitleOrchestrator(get<WyzieSearchRepository>(), get<MpvRxSubtitleHubRepository>()) }
    single { IntroDbRepository(get(), get()) }
    single { OpenCodeClient(get(), get()) }
    single { GroqClient(get(), get()) }
    single { OpenAiClient(get(), get()) }
    single { AnthropicClient(get(), get()) }
    single { OpenRouterClient(get(), get()) }
    single { TogetherClient(get(), get()) }
    single { GroqSpeechClient(get(), get()) }
    single { OpenRouterSpeechClient(get(), get()) }
    single<AiClient>(named("opencode")) { OpenCodeClient(get(), get()) }
    single<AiClient>(named("groq")) { GroqClient(get(), get()) }
    single<AiClient>(named("openai")) { OpenAiClient(get(), get()) }
    single<AiClient>(named("anthropic")) { AnthropicClient(get(), get()) }
    single<AiClient>(named("openrouter")) { OpenRouterClient(get(), get()) }
    single<AiClient>(named("together")) { TogetherClient(get(), get()) }
    single { SubtitleGenerationService(androidContext(), get(), get(), get(), get(), get()) }
    single { RealtimeSubtitleService(androidContext(), get(), get(), get(), get(), get()) }
    single { EmbeddedSubtitleTranslator(get(), get(), get()) }
    single {
      AiService(
        androidContext(),
        get<AiPreferences>(),
        get<AiClient>(named("opencode")),
        get<AiClient>(named("groq")),
        get<AiClient>(named("openai")),
        get<AiClient>(named("anthropic")),
        get<AiClient>(named("openrouter")),
        get<AiClient>(named("together")),
        get<Json>(),
      )
    }
    single {
      app.infinity.mpvz.domain.syncplay
        .SyncplayManager(androidContext())
    }
    single { app.infinity.mpvz.data.lyrics.LrcLibApiService(get()) }
    single { app.infinity.mpvz.data.lyrics.LyricsTranslationService(get()) }
    single { app.infinity.mpvz.repository.lyrics.LyricsRepository(androidContext(), get()) }
    single { TorrentStreamingEngine(androidContext()) }
    single { app.infinity.mpvz.repository.SeerrRepository(get(), get(), get()) }
  }
