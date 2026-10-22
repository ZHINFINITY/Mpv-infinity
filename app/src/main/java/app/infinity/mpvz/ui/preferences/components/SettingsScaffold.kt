/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.preferences.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold as MaterialScaffold
import androidx.compose.material3.TopAppBar as MaterialTopAppBar

/** Material Scaffold with a transparent root only while settings glass is enabled. */
@Composable
fun SettingsScaffold(
  modifier: Modifier = Modifier,
  topBar: @Composable () -> Unit = {},
  bottomBar: @Composable () -> Unit = {},
  snackbarHost: @Composable () -> Unit = {},
  floatingActionButton: @Composable () -> Unit = {},
  floatingActionButtonPosition: FabPosition = FabPosition.End,
  containerColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.background,
  contentColor: Color = androidx.compose.material3.contentColorFor(containerColor),
  contentWindowInsets: WindowInsets = androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
  content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
  val glassEnabled = settingsGlassEnabled()
  MaterialScaffold(
    modifier = modifier,
    topBar = topBar,
    bottomBar = bottomBar,
    snackbarHost = snackbarHost,
    floatingActionButton = floatingActionButton,
    floatingActionButtonPosition = floatingActionButtonPosition,
    containerColor = if (glassEnabled) Color.Transparent else containerColor,
    contentColor = contentColor,
    contentWindowInsets = contentWindowInsets,
    content = content,
  )
}

/** Top app bar whose root surface participates in settings glass without changing geometry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopAppBar(
  title: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  navigationIcon: @Composable () -> Unit = {},
  actions: @Composable RowScope.() -> Unit = {},
  windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
  colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
  scrollBehavior: TopAppBarScrollBehavior? = null,
  expandedHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight,
) {
  val glassEnabled = settingsGlassEnabled()
  MaterialTopAppBar(
    title = title,
    modifier = if (glassEnabled) modifier.settingsGlassSurface(cornerRadius = 0.dp) else modifier,
    navigationIcon = navigationIcon,
    actions = actions,
    windowInsets = windowInsets,
    colors = if (glassEnabled) {
      colors.copy(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
    } else {
      colors
    },
    scrollBehavior = scrollBehavior,
    expandedHeight = expandedHeight,
  )
}
