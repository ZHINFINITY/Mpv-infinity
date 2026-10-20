/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.di

import app.infinity.mpvz.domain.download.AppDownloadManager
import app.infinity.mpvz.domain.download.DownloadLocations
import app.infinity.mpvz.domain.download.LinkDownloadCoordinator
import app.infinity.mpvz.domain.download.YtdlpDownloadEngine
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val DownloadModule =
  module {
    single { DownloadLocations(androidContext(), get()) }
    single { AppDownloadManager(androidContext(), get<app.infinity.mpvz.database.MpvInfinityDatabase>().downloadItemDao(), get()) }
    single { YtdlpDownloadEngine(androidContext(), get()) }
    single { LinkDownloadCoordinator(get(), get()) }
  }
