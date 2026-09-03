/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.utils

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable

/**
 * Back-compat shim for the Material3 <1.5 `rememberBottomSheetState` API. The newer
 * `rememberModalBottomSheetState` dropped the [enabledValues] parameter; skipping
 * [SheetValue.PartiallyExpanded] is expressed through `skipPartiallyExpanded` instead.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun rememberBottomSheetState(
  initialValue: SheetValue = SheetValue.Hidden,
  enabledValues: Set<SheetValue> = SheetValue.entries.toSet(),
  confirmValueChange: (SheetValue) -> Boolean = { true },
): SheetState =
  rememberModalBottomSheetState(
    skipPartiallyExpanded = SheetValue.PartiallyExpanded !in enabledValues,
    confirmValueChange = confirmValueChange,
    initialValue = initialValue,
  )
