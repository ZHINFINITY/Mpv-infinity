/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import app.infinity.mpvz.preferences.preference.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier

/**
 * Mpv∞ motion policy — respects system reduce-motion accessibility setting.
 * When reduce-motion is true, all animations use non-bouncing standard specs.
 */
@Stable
data class MotionPolicy(
  val reduceMotion: Boolean = false,
)

val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy() }

@Composable
fun rememberMotionPolicy(): MotionPolicy {
  LocalView.current
  return MotionPolicy(reduceMotion = !ValueAnimator.areAnimatorsEnabled())
}

/**
 * Centralized motion specification object with spring-based animations.
 * Spring animations feel more natural than duration-based tweens.
 */
object AppMotion {
  @Composable
  fun policy(): MotionPolicy = LocalMotionPolicy.current

  @Composable
  fun <T> spatial(
    spec: FiniteAnimationSpec<T>,
    reduced: FiniteAnimationSpec<T>,
  ): FiniteAnimationSpec<T> = if (policy().reduceMotion) reduced else spec

  @Composable
  fun shouldReduceMotion(): Boolean = policy().reduceMotion

  @Composable
  fun playerReducedMotion(): Boolean {
    val prefs = org.koin.java.KoinJavaComponent.get<app.infinity.mpvz.preferences.PlayerPreferences>(
      app.infinity.mpvz.preferences.PlayerPreferences::class.java,
    )
    val reducedByPlayer by prefs.reduceMotion.collectAsState()
    return shouldReduceMotion() || reducedByPlayer
  }

  fun <T> noBounce(stiffness: Float): SpringSpec<T> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)

  val EnterEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
  /** A small, interruptible hand-off for changing actions; never changes their touch target. */
  fun iconChange(reduceMotion: Boolean): androidx.compose.animation.ContentTransform =
    if (reduceMotion) {
      EnterTransition.None togetherWith ExitTransition.None
    } else {
      (fadeIn(tween(160)) + scaleIn(noBounce(700f), initialScale = 0.78f)) togetherWith
        (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.92f))
    }

  val ReducedAlpha: SpringSpec<Float> = noBounce(stiffness = Spring.StiffnessMedium)
  val ReducedOffset: SpringSpec<IntOffset> = noBounce(stiffness = Spring.StiffnessMedium)
  val ReducedIntSize: SpringSpec<IntSize> = noBounce(stiffness = Spring.StiffnessMedium)
  val ReducedDp: SpringSpec<Dp> = noBounce(stiffness = Spring.StiffnessMedium)

  /** IntSize-specific spring for expandVertically/shrinkVertically animations. */
  val IntSizeSpring: SpringSpec<IntSize> =
    spring(
      dampingRatio = Spring.DampingRatioLowBouncy,
      stiffness = Spring.StiffnessMediumLow,
    )

  /** Spatial animations — can overshoot for expressive feel. */
  object Spatial {
    val ExpressiveDefault: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 700f)
    val ExpressiveFast: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 1400f)
    val ExpressiveSlow: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 300f)
    val StandardDefault: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 380f)
    val Expressive: SpringSpec<Float> = ExpressiveDefault
    val Standard: SpringSpec<Float> = StandardDefault
    val Snappy: SpringSpec<Float> = ExpressiveFast
    val SnappyDp: SpringSpec<Dp> = spring(dampingRatio = 0.9f, stiffness = 1400f)

    /** Dp-specific variants for animateDpAsState. */
    val ExpressiveDp: SpringSpec<Dp> = spring(dampingRatio = 0.9f, stiffness = 700f)
    val StandardDp: SpringSpec<Dp> = spring(dampingRatio = 1f, stiffness = 380f)

    /** Press / release feedback used by expressive transport buttons. */
    val Press = tween<Float>(durationMillis = 90, easing = EnterEasing)
    val Release: SpringSpec<Float> = noBounce(stiffness = 700f)
    val ExpressiveOffset: SpringSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = 700f)
    val ExpressiveSize: SpringSpec<IntSize> = spring(dampingRatio = 0.9f, stiffness = 700f)
    val StandardOffset: SpringSpec<IntOffset> = spring(dampingRatio = 1f, stiffness = 380f)
    val StandardSize: SpringSpec<IntSize> = spring(dampingRatio = 1f, stiffness = 380f)
    val IntSizeSpring: SpringSpec<IntSize> = StandardSize
  }

  /** Effect animations — color and alpha transitions (no overshoot). */
  object Effect {
    val Color: SpringSpec<Color> =
      spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
      )
    val Alpha: SpringSpec<Float> =
      spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
      )
  }
}

/**
 * Elevation tokens — consistent shadow/tonal elevation levels.
 */
object ElevationTokens {
  val Level0 = 0.dp
  val Level1 = 1.dp
  val Level2 = 3.dp
  val Level3 = 6.dp
  val Level4 = 8.dp
  val Level5 = 12.dp
}

/**
 * Shares the control's existing interaction source, so clicks, long presses, ripple and D-pad
 * behavior stay with the original control. Only drawing scales; layout and hit bounds stay put.
 */
@Composable
fun Modifier.appPressFeedback(
  interactionSource: InteractionSource,
  enabled: Boolean = true,
  pressedScale: Float = 0.97f,
  reduceMotion: Boolean = AppMotion.shouldReduceMotion(),
): Modifier {
  if (!enabled || reduceMotion) return this
  val pressed by interactionSource.collectIsPressedAsState()
  val scale = animateFloatAsState(
    targetValue = if (pressed) pressedScale else 1f,
    animationSpec = if (pressed) AppMotion.Spatial.Press else AppMotion.Spatial.Release,
    label = "appPressFeedback",
  )
  return graphicsLayer {
    scaleX = scale.value
    scaleY = scale.value
  }
}

/** Dialogs enter once per opening, not on recomposition or when their fields change. */
@Composable
fun Modifier.appDialogEnter(
  reduceMotion: Boolean = AppMotion.shouldReduceMotion(),
): Modifier {
  if (reduceMotion) return this
  val progress = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    progress.animateTo(1f, tween(durationMillis = 220, easing = AppMotion.EnterEasing))
  }
  return graphicsLayer {
    alpha = progress.value
    scaleX = 0.94f + 0.06f * progress.value
    scaleY = scaleX
  }
}
