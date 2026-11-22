/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.preferences
import app.infinity.mpvz.ui.theme.GlassDropdownMenu
import app.infinity.mpvz.ui.theme.GlassExposedDropdownMenu

import app.infinity.mpvz.ui.preferences.components.settingsGlassContainerColor

import app.infinity.mpvz.ui.preferences.components.settingsGlassSurface

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.infinity.mpvz.ui.preferences.components.SettingsScaffold as Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.infinity.mpvz.ui.preferences.components.SettingsTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.infinity.mpvz.R
import app.infinity.mpvz.domain.audiobookshelf.AudiobookshelfServer
import app.infinity.mpvz.domain.jellyfin.JellyfinServer
import app.infinity.mpvz.domain.navidrome.NavidromeServer
import app.infinity.mpvz.domain.seerr.JellyseerrUser
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.presentation.components.RemoteImage
import app.infinity.mpvz.preferences.AppearancePreferences
import org.koin.compose.koinInject
import app.infinity.mpvz.ui.browser.audiobooks.AddAudiobookshelfServerDialog
import app.infinity.mpvz.ui.browser.audiobooks.AudiobookshelfViewModel
import app.infinity.mpvz.ui.browser.jellyfin.AddJellyfinServerDialog
import app.infinity.mpvz.ui.browser.jellyfin.JellyfinViewModel
import app.infinity.mpvz.ui.browser.jellyfin.seerr.SeerrConnectionDialog
import app.infinity.mpvz.ui.browser.jellyfin.seerr.SeerrViewModel
import app.infinity.mpvz.ui.browser.navidrome.AddNavidromeServerDialog
import app.infinity.mpvz.ui.browser.navidrome.NavidromeViewModel
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.LocalShowSettingsBackArrow
import app.infinity.mpvz.ui.utils.popSafely
import kotlinx.serialization.Serializable
import app.infinity.mpvz.ui.preferences.components.Preference
import me.zhanghai.compose.preference.ProvidePreferenceLocals

@Serializable
object MediaServersPreferencesScreen : Screen {

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val appearancePreferences = koinInject<AppearancePreferences>()

    val jellyfinViewModel: JellyfinViewModel =
      viewModel(factory = JellyfinViewModel.factory(context.applicationContext as Application))
    val jellyfinUiState by jellyfinViewModel.uiState.collectAsStateWithLifecycle()

    val seerrViewModel: SeerrViewModel =
      viewModel(factory = SeerrViewModel.factory(context.applicationContext as Application))
    val seerrUiState by seerrViewModel.uiState.collectAsStateWithLifecycle()

    val navidromeViewModel: NavidromeViewModel =
      viewModel(factory = NavidromeViewModel.factory(context.applicationContext as Application))
    val navidromeUiState by navidromeViewModel.uiState.collectAsStateWithLifecycle()

    val audiobookshelfViewModel: AudiobookshelfViewModel =
      viewModel(factory = AudiobookshelfViewModel.factory(context.applicationContext as Application))
    val audiobookshelfUiState by audiobookshelfViewModel.uiState.collectAsStateWithLifecycle()

    var isAddServerOpen by remember { mutableStateOf(false) }
    var serverToReauth by remember { mutableStateOf<JellyfinServer?>(null) }
    var isSeerrConnectionDialogOpen by remember { mutableStateOf(false) }
