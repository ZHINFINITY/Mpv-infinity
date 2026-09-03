/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 search bar used inline at the top of list screens.
 *
 * Wraps the M3 [SearchBar]/[SearchBarDefaults.InputField] pair in its collapsed state so
 * every screen gets proper search semantics, the IME Search action (which submits and
 * hides the keyboard) and standard M3 styling from a single component.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InlineSearchBar(
  query: String,
  onQueryChange: (String) -> Unit,
  onSearch: (String) -> Unit,
  modifier: Modifier = Modifier,
  inputFieldModifier: Modifier = Modifier,
  placeholder: (@Composable () -> Unit)? = null,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
  shape: Shape = SearchBarDefaults.inputFieldShape,
  tonalElevation: Dp = SearchBarDefaults.TonalElevation,
  shadowElevation: Dp = SearchBarDefaults.ShadowElevation,
  windowInsets: WindowInsets = SearchBarDefaults.windowInsets,
) {
  val keyboardController = LocalSoftwareKeyboardController.current

  SearchBar(
    inputField = {
      SearchBarDefaults.InputField(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = { submitted ->
          keyboardController?.hide()
          onSearch(submitted)
        },
        expanded = false,
        onExpandedChange = {},
        modifier = inputFieldModifier.fillMaxWidth(),
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
      )
    },
    expanded = false,
    onExpandedChange = {},
    // M3 SearchBar applied status-bar insets by default; keep that plus breathing room so
    // top-bar usages don't render under (or hug) the status bar / display cutout.
    modifier =
      Modifier
        .windowInsetsPadding(windowInsets)
        .then(modifier),
    shape = shape,
    tonalElevation = tonalElevation,
    shadowElevation = shadowElevation,
    windowInsets = WindowInsets(0.dp),
    content = {},
  )
}
