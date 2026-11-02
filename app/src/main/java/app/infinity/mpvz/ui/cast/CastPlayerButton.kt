/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.cast

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import androidx.mediarouter.app.MediaRouteDialogFactory
import app.infinity.mpvz.preferences.PlayerButton
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.theme.controlColor
import app.infinity.mpvz.ui.player.controls.components.playerGlassPane
import com.google.android.gms.cast.framework.CastButtonFactory
import androidx.compose.ui.graphics.Color as ComposeColor
import app.infinity.mpvz.ui.theme.GlassSurfacePolicy
import org.koin.compose.koinInject

/** Uses the SDK button for Cast behavior while keeping the app's rounded symbol visible. */
@Composable
fun CastPlayerButton(
  hideBackground: Boolean,
  buttonSize: Dp,
  onInvoked: () -> Unit = {},
  contentColor: ComposeColor? = null,
) {
  val appearance = koinInject<AppearancePreferences>()
  val liquidGlassSurfaces by appearance.liquidGlassSurfaces.collectAsState()
  val glassOpacity by appearance.glassSurfaceOpacity.collectAsState()
  val glassHighlight by appearance.glassSurfaceHighlight.collectAsState()
  val castContentDescription =
    androidx.compose.ui.res
      .stringResource(app.infinity.mpvz.R.string.ui_cast)
  Surface(
    shape = CircleShape,
    color =
      if (hideBackground || liquidGlassSurfaces) {
        ComposeColor.Transparent
      } else {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
      },
    // Callers pass the surrounding content color (e.g. drawer tiles) so the icon
    // matches its sibling buttons instead of staying fixed white.
    contentColor = contentColor ?: if (hideBackground) controlColor else MaterialTheme.colorScheme.onSurface,
    border =
      if (hideBackground || liquidGlassSurfaces) {
        null
      } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
      },
    modifier = Modifier.size(buttonSize).playerGlassPane(
      enabled = liquidGlassSurfaces && !hideBackground,
      cornerRadius = 999.dp,
    ),
  ) {
    Box(contentAlignment = Alignment.Center) {
      AndroidView(
        factory = { context ->
          MediaRouteButton(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = castContentDescription
            CastButtonFactory.setUpMediaRouteButton(context.applicationContext, this)
            setDialogFactory(
              mediaRouteDialogFactory(liquidGlassSurfaces, glassOpacity, glassHighlight),
            )
            setRemoteIndicatorDrawable(ColorDrawable(Color.TRANSPARENT))
          }
        },
        update = { button ->
          button.setDialogFactory(
            mediaRouteDialogFactory(liquidGlassSurfaces, glassOpacity, glassHighlight),
          )
          button.setOnClickListener { onInvoked() }
        },
        modifier = Modifier.fillMaxSize(),
      )
      Icon(
        imageVector = PlayerButton.CAST.icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
      )
    }
  }
}

private fun mediaRouteDialogFactory(
  liquidGlassSurfaces: Boolean,
  glassOpacity: Float,
  glassHighlight: Float,
): MediaRouteDialogFactory =
  if (liquidGlassSurfaces) GlassMediaRouteDialogFactory(glassOpacity, glassHighlight)
  else MediaRouteDialogFactory.getDefault()

private const val ARG_GLASS_OPACITY = "app.infinity.mpvz.cast.glass_opacity"
private const val ARG_GLASS_HIGHLIGHT = "app.infinity.mpvz.cast.glass_highlight"

/** MediaRouter owns the chooser window; this factory returns a restorable top-level Fragment. */
private class GlassMediaRouteDialogFactory(
  private val opacity: Float,
  private val highlight: Float,
) : MediaRouteDialogFactory() {
  override fun onCreateChooserDialogFragment(): MediaRouteChooserDialogFragment =
    GlassMediaRouteChooserDialogFragment().apply {
      arguments = Bundle().apply {
        putFloat(ARG_GLASS_OPACITY, opacity)
        putFloat(ARG_GLASS_HIGHLIGHT, highlight)
      }
    }
}

/** Must remain public, top-level, and no-argument so FragmentManager can restore it after recreation. */
class GlassMediaRouteChooserDialogFragment : MediaRouteChooserDialogFragment() {
  override fun onCreateChooserDialog(context: Context, savedInstanceState: Bundle?): MediaRouteChooserDialog {
    val opacity = arguments?.getFloat(ARG_GLASS_OPACITY, 0.45f) ?: 0.45f
    val highlight = arguments?.getFloat(ARG_GLASS_HIGHLIGHT, 0.45f) ?: 0.45f
    return GlassMediaRouteChooserDialog(context, opacity, highlight)
  }
}

private class GlassMediaRouteChooserDialog(
  private val dialogContext: Context,
  private val glassOpacity: Float,
  private val glassHighlight: Float,
) : MediaRouteChooserDialog(dialogContext) {
  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    window?.decorView?.background =
      GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f * dialogContext.resources.displayMetrics.density
        val backgroundColor = dialogBackgroundColor(dialogContext)
        val alpha = (GlassSurfacePolicy.opacity(glassOpacity) * 255).toInt().coerceIn(1, 255)
        setColor(Color.argb(alpha, Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor)))
        setStroke(
          dialogContext.resources.displayMetrics.density.toInt().coerceAtLeast(1),
          Color.argb((GlassSurfacePolicy.highlight(glassHighlight) * 150).toInt().coerceIn(1, 150), 255, 255, 255),
        )
      }
  }
}

internal fun resolveDialogThemeColor(
  resourceId: Int,
  type: Int,
  data: Int,
  fallbackColor: Int,
  resolveResourceColor: (Int) -> Int,
): Int =
  when {
    resourceId != 0 -> runCatching { resolveResourceColor(resourceId) }.getOrDefault(fallbackColor)
    type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> data
    else -> fallbackColor
  }

private fun dialogBackgroundColor(context: Context): Int {
  val fallbackColor = Color.rgb(32, 36, 44)
  val value = TypedValue()
  if (!context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)) return fallbackColor
  return resolveDialogThemeColor(
    resourceId = value.resourceId,
    type = value.type,
    data = value.data,
    fallbackColor = fallbackColor,
  ) { resourceId -> ContextCompat.getColor(context, resourceId) }
}
