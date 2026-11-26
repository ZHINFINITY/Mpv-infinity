/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.tv

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.icons.AppIcon
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.controls.components.tvFocusHighlight

/**
 * TV replacement for a drag handle, which a remote cannot use: Move up and Move down steps. At the
 * ends a button stays focusable and does nothing, because disabling the focused button would drop
 * focus. The moved row keeps focus as long as the list is keyed by item.
 */
@Composable
internal fun TvReorderButtons(
  canMoveUp: Boolean,
  canMoveDown: Boolean,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
    TvReorderButton(
      icon = Icons.RoundedFilled.KeyboardArrowUp,
      description = stringResource(R.string.tv_move_up),
      active = canMoveUp,
      onClick = onMoveUp,
    )
    TvReorderButton(
      icon = Icons.RoundedFilled.KeyboardArrowDown,
      description = stringResource(R.string.tv_move_down),
      active = canMoveDown,
      onClick = onMoveDown,
    )
  }
}

@Composable
private fun TvReorderButton(
  icon: AppIcon,
  description: String,
  active: Boolean,
  onClick: () -> Unit,
) {
  IconButton(
    onClick = { if (active) onClick() },
    modifier = Modifier.tvFocusHighlight(CircleShape, focusedScale = 1.08f),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = description,
      tint = MaterialTheme.colorScheme.primary.copy(alpha = if (active) 1f else 0.38f),
    )
  }
}
