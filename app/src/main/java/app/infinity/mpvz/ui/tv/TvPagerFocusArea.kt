/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.tv

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch

/**
 * TV key handling for a full-screen [androidx.compose.foundation.pager.HorizontalPager] (the
 * snapshot viewer): Left/Right turn pages, Up/Down are left to the focus search so a remote can
 * reach the overlay's top bar and bottom actions. Touch devices never get this — the pager is
 * swiped there, and a focus-stealing overlay would break the tap gestures.
 */
@Composable
internal fun TvPagerFocusArea(
  pagerState: PagerState,
  modifier: Modifier = Modifier,
) {
  if (!isTelevision()) return
  val scope = rememberCoroutineScope()

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .focusable()
        .onKeyEvent { event ->
          if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
          val target =
            when (event.key) {
              Key.DirectionLeft -> pagerState.currentPage - 1
              Key.DirectionRight -> pagerState.currentPage + 1
              else -> return@onKeyEvent false
            }
          if (target !in 0..pagerState.pageCount - 1) return@onKeyEvent true
          scope.launch { pagerState.animateScrollToPage(target) }
          true
        },
  )
}
