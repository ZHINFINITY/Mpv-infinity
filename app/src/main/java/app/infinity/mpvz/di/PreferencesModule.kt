/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.di

import app.infinity.mpvz.preferences.AdvancedPreferences
import app.infinity.mpvz.preferences.AiPreferences
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.AudioPreferences
import app.infinity.mpvz.preferences.BrowserPreferences
import app.infinity.mpvz.preferences.DecoderPreferences
import app.infinity.mpvz.preferences.DownloadPreferences
import app.infinity.mpvz.preferences.FoldersPreferences
import app.infinity.mpvz.preferences.GesturePreferences
import app.infinity.mpvz.preferences.NetworkBookmarkPreferences
import app.infinity.mpvz.preferences.MediaServerPreferences
import app.infinity.mpvz.preferences.PlayerPreferences
import app.infinity.mpvz.preferences.SecureFolderPreferences
import app.infinity.mpvz.preferences.SeerrPreferences
import app.infinity.mpvz.preferences.SettingsManager
import app.infinity.mpvz.preferences.SubtitlesPreferences
import app.infinity.mpvz.preferences.YtdlPreferences
import app.infinity.mpvz.preferences.preference.AndroidPreferenceStore
import app.infinity.mpvz.preferences.preference.PreferenceStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val PreferencesModule =
  module {
    single { AndroidPreferenceStore(androidContext()) }.bind(PreferenceStore::class)

    single { AppearancePreferences(get()) }
    singleOf(::PlayerPreferences)
    singleOf(::GesturePreferences)
    singleOf(::DecoderPreferences)
    singleOf(::SubtitlesPreferences)
    singleOf(::AudioPreferences)
    singleOf(::AdvancedPreferences)
    single { BrowserPreferences(get(), androidContext()) }
    singleOf(::FoldersPreferences)
    singleOf(::NetworkBookmarkPreferences)
    singleOf(::MediaServerPreferences)
    singleOf(::AiPreferences)
    singleOf(::YtdlPreferences)
    singleOf(::SettingsManager)
    singleOf(::SecureFolderPreferences)
    singleOf(::SeerrPreferences)
    singleOf(::DownloadPreferences)
  }
