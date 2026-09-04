/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
    singleOf(::AiPreferences)
    singleOf(::YtdlPreferences)
    singleOf(::SettingsManager)
    singleOf(::SecureFolderPreferences)
    singleOf(::SeerrPreferences)
    singleOf(::DownloadPreferences)
  }
