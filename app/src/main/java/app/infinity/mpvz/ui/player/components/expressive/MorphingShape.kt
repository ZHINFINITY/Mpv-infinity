/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.components.expressive

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp

private val dpExpressiveSpring = spring<Dp>(dampingRatio = 0.9f, stiffness = 700f)

/**
 * Spring-animated rounded corner shape.
 *
 * Use this for corners that morph between states (e.g., enabled → disabled, selected → unselected).
 * The corner radius transitions with an expressive spring (can overshoot).
 *
 * @param targetRadius The target corner radius to animate towards.
 * @return The current animated shape (recomputed each frame).
 */
@Composable
fun animatedRoundedCornerShape(targetRadius: Dp): RoundedCornerShape {
  val animatedRadius by animateDpAsState(
    targetValue = targetRadius,
    animationSpec = dpExpressiveSpring,
    label = "AnimatedRoundedCornerShape",
  )
  return RoundedCornerShape(animatedRadius)
}

/**
 * Spring-animated rounded corner shape with per-corner control.
 */
@Composable
fun animatedRoundedCornerShape(
  topStart: Dp,
  topEnd: Dp,
  bottomStart: Dp,
  bottomEnd: Dp,
): RoundedCornerShape {
  val animatedTopStart by animateDpAsState(
    targetValue = topStart,
    animationSpec = dpExpressiveSpring,
    label = "AnimatedCornerTopStart",
  )
  val animatedTopEnd by animateDpAsState(
    targetValue = topEnd,
    animationSpec = dpExpressiveSpring,
    label = "AnimatedCornerTopEnd",
  )
  val animatedBottomStart by animateDpAsState(
    targetValue = bottomStart,
    animationSpec = dpExpressiveSpring,
    label = "AnimatedCornerBottomStart",
  )
  val animatedBottomEnd by animateDpAsState(
    targetValue = bottomEnd,
    animationSpec = dpExpressiveSpring,
    label = "AnimatedCornerBottomEnd",
  )
  return RoundedCornerShape(
    topStart = animatedTopStart,
    topEnd = animatedTopEnd,
    bottomStart = animatedBottomStart,
    bottomEnd = animatedBottomEnd,
  )
}
