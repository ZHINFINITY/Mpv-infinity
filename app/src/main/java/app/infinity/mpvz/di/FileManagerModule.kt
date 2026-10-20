/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.di

import com.github.k1rakishou.fsaf.FileManager
import org.koin.dsl.module

val FileManagerModule =
  module {
    single { FileManager(get()) }
  }
