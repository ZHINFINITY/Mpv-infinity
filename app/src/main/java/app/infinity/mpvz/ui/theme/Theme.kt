/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.CustomThemeData
import app.infinity.mpvz.preferences.CustomThemeVideoView
import app.infinity.mpvz.preferences.applyTheme
import app.infinity.mpvz.preferences.updateThemeEffects
import app.infinity.mpvz.preferences.preference.collectAsState
import org.koin.compose.koinInject
import kotlin.math.hypot

// ============================================================================
// Theme Transition Animation State & Components
// ============================================================================

/**
 * State for managing theme transition animations.
 * This class holds the animation state and provides methods to trigger transitions.
 */
class ThemeTransitionState {
  var isAnimating by mutableStateOf(false)
    private set
  var clickPosition by mutableStateOf(Offset.Zero)
    private set
  var screenshotBitmap by mutableStateOf<Bitmap?>(null)
    private set
  var animationProgress = Animatable(0f)
    private set

  private var captureView: View? = null

  fun setView(view: View?) {
    captureView = view
  }

  /**
   * Start a theme transition animation from the given position.
   * Captures the current screen and begins the reveal animation.
   * Will NOT start a new animation if one is already in progress.
   */
  fun startTransition(position: Offset) {
    // Don't allow new animation while one is in progress
    if (isAnimating) return

    captureView?.let { view ->
      try {
        // Capture before setting isAnimating to ensure we get the current state
        val bitmap = view.drawToBitmap()
        animationProgress = Animatable(0f)
        screenshotBitmap = bitmap
        clickPosition = position
        isAnimating = true
      } catch (e: Exception) {
        // If capture fails, just skip the animation
        screenshotBitmap = null
        isAnimating = false
      }
    }
  }

  fun finishTransition() {
    val oldBitmap = screenshotBitmap
    screenshotBitmap = null
    clickPosition = Offset.Zero
    isAnimating = false
    // Let Compose detach the ImageBitmap before releasing its Android backing bitmap.
    captureView?.postDelayed(
      { oldBitmap?.takeUnless { it.isRecycled }?.recycle() },
      BITMAP_RECYCLE_DELAY_MS,
    )
  }

  suspend fun resetProgress() {
    animationProgress.snapTo(0f)
  }

  private companion object {
    const val BITMAP_RECYCLE_DELAY_MS = 96L
  }
}

/**
 * CompositionLocal to provide ThemeTransitionState down the composition tree
 */
val LocalThemeTransitionState = staticCompositionLocalOf<ThemeTransitionState?> { null }

/** Dark counterpart of the selected app palette, even while the app itself is using light mode. */
internal val LocalDarkAppColorScheme = staticCompositionLocalOf<ColorScheme?> { null }

@Composable
fun rememberThemeTransitionState(): ThemeTransitionState = remember { ThemeTransitionState() }

/**
 * Reveals the new theme below a frozen frame of the old theme. The old frame is
 * removed by a feathered radial alpha mask, which gives the transition the soft
 * blur edge used by Telegram-style theme switches without blurring text or video.
 */
@Composable
private fun ThemeTransitionOverlay(
  state: ThemeTransitionState,
  content: @Composable () -> Unit,
) {
  val view = LocalView.current
  val reduceMotion = LocalMotionPolicy.current.reduceMotion
  val featherPx = with(LocalDensity.current) { THEME_REVEAL_FEATHER.toPx() }
  val bitmap = state.screenshotBitmap
  val progress = state.animationProgress.value

  DisposableEffect(view, state) {
    state.setView(view)
    onDispose { state.setView(null) }
  }

  LaunchedEffect(state.isAnimating, bitmap, reduceMotion) {
    if (!state.isAnimating || bitmap == null) return@LaunchedEffect

    state.resetProgress()
    // Give the newly selected Material color scheme one frame to settle below the snapshot.
    withFrameNanos { }
    if (reduceMotion) {
      state.animationProgress.snapTo(1f)
    } else {
      // Keep the frozen frame opaque while the new theme finishes its root recomposition.
      kotlinx.coroutines.delay(THEME_CONTENT_SETTLE_DELAY_MS.toLong())
      state.animationProgress.animateTo(
        targetValue = 1f,
        animationSpec =
          tween(
            durationMillis = THEME_REVEAL_DURATION_MS,
            easing = FastOutSlowInEasing,
          ),
      )
    }
    state.finishTransition()
  }

  Box(modifier = Modifier.fillMaxSize()) {
    content()

    if (bitmap != null && state.isAnimating) {
      val frozenFrame = remember(bitmap) { bitmap.asImageBitmap() }
      Image(
        bitmap = frozenFrame,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier =
          Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
              val center =
                state.clickPosition.takeUnless { it == Offset.Zero }
                  ?: Offset(size.width / 2f, size.height / 2f)
              val maxRadius =
                maxOf(
                  hypot(center.x, center.y),
                  hypot(size.width - center.x, center.y),
                  hypot(center.x, size.height - center.y),
                  hypot(size.width - center.x, size.height - center.y),
                )
              val revealRadius = (maxRadius + featherPx) * progress
              val maskRadius = (revealRadius + featherPx).coerceAtLeast(1f)
              val clearStop = ((revealRadius - featherPx) / maskRadius).coerceIn(0f, 1f)
              val softStop = (revealRadius / maskRadius).coerceIn(clearStop, 1f)
              val mask =
                Brush.radialGradient(
                  colorStops =
                    arrayOf(
                      0f to Color.Transparent,
                      clearStop to Color.Transparent,
                      softStop to Color.Black.copy(alpha = 0.28f),
                      1f to Color.Black,
                    ),
                  center = center,
                  radius = maskRadius,
                )

              onDrawWithContent {
                drawContent()
                if (progress > 0f) {
                  drawRect(brush = mask, blendMode = BlendMode.DstIn)
                }
              }
            }.pointerInput(Unit) {
              awaitPointerEventScope {
                while (true) {
                  awaitPointerEvent().changes.forEach { it.consume() }
                }
              }
            },
      )
    }
  }
}

private const val THEME_CONTENT_SETTLE_DELAY_MS = 100
private const val THEME_REVEAL_DURATION_MS = 650
private val THEME_REVEAL_FEATHER = 30.dp

@Composable
private fun ThemeTransitionContent(content: @Composable () -> Unit) {
  val state = LocalThemeTransitionState.current

  if (state != null) {
    ThemeTransitionOverlay(state = state, content = content)
  } else {
    content()
  }
}

// ============================================================================
// Main Theme
// ============================================================================

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MpvInfinityTheme(
  transitionState: ThemeTransitionState = rememberThemeTransitionState(),
  showCustomThemeBackdrop: Boolean = true,
  content: @Composable () -> Unit,
) {
  val preferences = koinInject<AppearancePreferences>()
  val darkMode by preferences.darkMode.collectAsState()
  val amoledMode by preferences.amoledMode.collectAsState()
  val appTheme by preferences.appTheme.collectAsState()
  val customThemes by preferences.customThemes.collectAsState()
  val activeCustomThemeId by preferences.activeCustomThemeId.collectAsState()
  val customTheme = customThemes.firstOrNull { it.id == activeCustomThemeId }
  val useSystemFont by preferences.useSystemFont.collectAsState()
  val darkTheme = isSystemInDarkTheme()
  val configuration = LocalConfiguration.current
  val context = LocalContext.current
  val localeNeedsSystemFont =
    remember(configuration) {
      localeRequiresSystemFont(configuration.locales[0])
    }

  val useDarkTheme =
    when (darkMode) {
      DarkMode.Dark -> true
      DarkMode.Light -> false
      DarkMode.System -> darkTheme
    }

  // A custom selection is a complete theme, not a layer on top of the
  // currently selected built-in palette. Resolve it before appTheme so
  // Dynamic, AMOLED, and other built-in surfaces cannot leak into it.
  val customScheme = customTheme?.let(::customColorScheme)
  val darkColorScheme =
    customScheme
      ?: resolveAppColorScheme(
        context = context,
        appTheme = appTheme,
        useDarkTheme = true,
        amoledMode = amoledMode,
      )
  val colorScheme =
    customScheme
      ?: resolveAppColorScheme(
        context = context,
        appTheme = appTheme,
        useDarkTheme = useDarkTheme,
        amoledMode = amoledMode,
      )

  // Provide theme transition state first, OUTSIDE MaterialExpressiveTheme
  CompositionLocalProvider(
    LocalSpacing provides Spacing(),
    LocalThemeTransitionState provides transitionState,
    LocalMotionPolicy provides rememberMotionPolicy(),
    LocalEmphasizedTypography provides AppEmphasizedTypography,
    LocalDarkAppColorScheme provides darkColorScheme,
  ) {
    val themedContent: @Composable () -> Unit = {
      ThemeTransitionContent {
        MaterialExpressiveTheme(
          colorScheme = colorScheme,
          typography = if (useSystemFont || localeNeedsSystemFont) SystemTypography else AppTypography,
          shapes = AppShapes,
          motionScheme = MotionScheme.expressive(),
          content = content,
        )
      }
    }
    if (showCustomThemeBackdrop) CustomThemeBackdrop(customTheme, themedContent) else themedContent()
  }
}

@Composable
private fun CustomThemeBackdrop(theme: CustomThemeData?, content: @Composable () -> Unit) {
  if (theme == null) {
    content()
    return
  }
  Box(Modifier.fillMaxSize()) {
    if (theme.isVideo) {
      AndroidView(
        modifier = Modifier.fillMaxSize().graphicsLayer {
          scaleX = 1f
          scaleY = 1f
          alpha = theme.visibility.coerceIn(0.15f, 1f)
        },
        factory = { context -> CustomThemeVideoView(context).also { it.applyTheme(theme) } },
        update = { view -> view.updateThemeEffects(theme) },
      )
    } else {
      val bitmap = remember(theme.mediaPath) { android.graphics.BitmapFactory.decodeFile(theme.mediaPath) }
      bitmap?.let {
        Image(
          bitmap = it.asImageBitmap(),
          contentDescription = null,
          contentScale = theme.contentScale(),
          alignment = BiasAlignment(theme.offsetX, theme.offsetY),
          colorFilter = theme.mediaColorFilter(),
          modifier = Modifier.fillMaxSize().blur(theme.blur.dp).graphicsLayer(scaleX = theme.scale, scaleY = theme.scale, alpha = theme.visibility.coerceIn(0.15f, 1f)),
        )
      }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (theme.overlay * 0.35f).coerceIn(0f, 0.35f))))
    content()
  }
}

private fun CustomThemeData.mediaColorFilter(): ColorFilter {
  val saturationMatrix = ColorMatrix()
  saturationMatrix.setToSaturation(saturation)
  val values = saturationMatrix.values.copyOf()
  values[0] *= brightness; values[1] *= brightness; values[2] *= brightness; values[4] *= brightness
  values[5] *= brightness; values[6] *= brightness; values[7] *= brightness; values[9] *= brightness
  values[10] *= brightness; values[11] *= brightness; values[12] *= brightness; values[14] *= brightness
  return ColorFilter.colorMatrix(ColorMatrix(values))
}

private fun CustomThemeData.contentScale(): ContentScale = when (fitMode) {
  "fit" -> ContentScale.Fit
  // Fill the viewport by cropping while preserving the source aspect ratio.
  "fill" -> ContentScale.Crop
  else -> ContentScale.Crop
}

private fun customColorScheme(theme: CustomThemeData): ColorScheme {
  val primary = Color(tuneCustomColor(theme.primaryArgb, theme))
  val backgroundColor = Color(tuneCustomColor(theme.backgroundArgb, theme, dim = true))
  val opacityScale = theme.surfaceOpacity.coerceIn(0.55f, 1f) / 0.94f
  fun panelAlpha(defaultAlpha: Float): Float = (defaultAlpha * opacityScale).coerceIn(0.35f, 1f)
  // Keep the media visible, but make app surfaces opaque enough that labels
  // and controls do not disappear into a bright or busy background.
  val background = backgroundColor.copy(alpha = panelAlpha(0.90f))
  val onBackground = if (backgroundColor.luminance() > 0.46f) Color.Black else Color.White
  val onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White
  return darkColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primary.copy(alpha = 0.48f),
    onPrimaryContainer = onBackground,
    secondary = primary.copy(alpha = 0.86f),
    onSecondary = onPrimary,
    secondaryContainer = background.copy(alpha = 0.78f),
    onSecondaryContainer = onBackground,
    tertiary = primary.copy(alpha = 0.72f),
    onTertiary = onPrimary,
    tertiaryContainer = background.copy(alpha = 0.78f),
    onTertiaryContainer = onBackground,
    // Keep the root background transparent, but route standard Material surfaces
    // through the same user-controlled opacity as the container surfaces. This
    // makes Panel opacity work for top bars, scaffolds, dialogs, images, and video themes.
    background = Color.Transparent,
    surface = background.copy(alpha = panelAlpha(0.94f)),
    surfaceDim = background.copy(alpha = panelAlpha(0.78f)),
    surfaceBright = background.copy(alpha = panelAlpha(0.985f)),
    // Keep the media visible through the root, but make interactive content
    // surfaces sufficiently opaque for folder names, paths, badges, and icons.
    surfaceVariant = background.copy(alpha = panelAlpha(0.86f)),
    surfaceContainerLowest = background.copy(alpha = panelAlpha(0.78f)),
    surfaceContainerLow = background.copy(alpha = panelAlpha(0.90f)),
    surfaceContainer = background.copy(alpha = panelAlpha(0.94f)),
    surfaceContainerHigh = background.copy(alpha = panelAlpha(0.97f)),
    surfaceContainerHighest = background.copy(alpha = panelAlpha(0.985f)),
    onBackground = onBackground,
    onSurface = onBackground,
    onSurfaceVariant = onBackground.copy(alpha = 0.92f),
    inverseSurface = background.copy(alpha = panelAlpha(0.72f)),
    inverseOnSurface = onBackground,
  )
}

private fun tuneCustomColor(argb: Int, theme: CustomThemeData, dim: Boolean = false): Int {
  val hsv = FloatArray(3)
  android.graphics.Color.colorToHSV(argb, hsv)
  hsv[1] = (hsv[1] * theme.saturation).coerceIn(0f, 1f)
  hsv[2] = (hsv[2] * theme.brightness * if (dim) (1f - theme.overlay * 0.35f) else 1f).coerceIn(0f, 1f)
  return android.graphics.Color.HSVToColor(android.graphics.Color.alpha(argb), hsv)
}

private fun resolveAppColorScheme(
  context: Context,
  appTheme: AppTheme,
  useDarkTheme: Boolean,
  amoledMode: Boolean,
): ColorScheme =
  when {
    appTheme.isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      when {
        useDarkTheme && amoledMode -> {
          dynamicDarkColorScheme(context).copy(
            background = backgroundPureBlack,
            surface = surfacePureBlack,
            surfaceDim = surfaceDimPureBlack,
            surfaceBright = surfaceBrightPureBlack,
            surfaceContainerLowest = surfaceContainerLowestPureBlack,
            surfaceContainerLow = surfaceContainerLowPureBlack,
            surfaceContainer = surfaceContainerPureBlack,
            surfaceContainerHigh = surfaceContainerHighPureBlack,
            surfaceContainerHighest = surfaceContainerHighestPureBlack,
          )
        }
        useDarkTheme -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context).withComfortableLightSurfaces()
      }
    }
    useDarkTheme && amoledMode -> appTheme.getAmoledColorScheme()
    useDarkTheme -> appTheme.getDarkColorScheme()
    else -> appTheme.getLightColorScheme()
  }

/** Keeps wallpaper-derived accents while replacing near-white full-screen surfaces. */
private fun androidx.compose.material3.ColorScheme.withComfortableLightSurfaces(): androidx.compose.material3.ColorScheme {
  val base = Color(0xFFF7F5F8)

  fun tint(amount: Float) =
    androidx.compose.ui.graphics
      .lerp(base, primary, amount)

  return copy(
    background = tint(0.018f),
    surface = tint(0.018f),
    surfaceDim = tint(0.085f),
    surfaceBright = Color(0xFFFBF9FC),
    surfaceContainerLowest = Color(0xFFFBF9FC),
    surfaceContainerLow = tint(0.030f),
    surfaceContainer = tint(0.050f),
    surfaceContainerHigh = tint(0.072f),
    surfaceContainerHighest = tint(0.098f),
  )
}

enum class DarkMode(
  @StringRes val titleRes: Int,
) {
  Dark(R.string.pref_appearance_darkmode_dark),
  Light(R.string.pref_appearance_darkmode_light),
  System(R.string.pref_appearance_darkmode_system),
}

private const val RIPPLE_DRAGGED_ALPHA = .5f
private const val RIPPLE_FOCUSED_ALPHA = .6f
private const val RIPPLE_HOVERED_ALPHA = .4f
private const val RIPPLE_PRESSED_ALPHA = .6f

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
val playerRippleConfiguration
  @Composable get() =
    RippleConfiguration(
      color = MaterialTheme.colorScheme.primaryContainer,
      rippleAlpha =
        RippleAlpha(
          draggedAlpha = RIPPLE_DRAGGED_ALPHA,
          focusedAlpha = RIPPLE_FOCUSED_ALPHA,
          hoveredAlpha = RIPPLE_HOVERED_ALPHA,
          pressedAlpha = RIPPLE_PRESSED_ALPHA,
        ),
    )
