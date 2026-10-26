/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme.lens

import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.Backdrop

/**
 * Shared gate and sizing rules for the lens glass engine.
 *
 * The lens look (refraction, magnification, optional chromatic edge) is drawn with the
 * Kyant0/AndroidLiquidGlass "backdrop" library (Apache-2.0). Its lens effect needs
 * RuntimeShader (API 33+). Every lens surface must check [LocalLensGlassEnabled] and keep the
 * existing frosted glass path when it is false: API < 33, a GlassDeviceSupport verdict other
 * than Supported (weak/crashing devices), or the user's Liquid Glass setting off.
 *
 * Never draw a backdrop inside the content that records it (that self-sampling loop crashes
 * the RenderThread). Nested glass uses `exportedBackdrop` / a separate layer.
 */
object LensGlassPolicy {
  const val MinSdk = Build.VERSION_CODES.TIRAMISU

  fun lensAllowed(sdkInt: Int, deviceSupported: Boolean, glassEnabled: Boolean): Boolean =
    sdkInt >= MinSdk && deviceSupported && glassEnabled

  /** Library contract: refraction height must stay within [0, shape min corner radius]. */
  fun refractionHeightPx(requestedPx: Float, minCornerRadiusPx: Float, lensDepth: Float): Float =
    (requestedPx * lensDepth.coerceIn(0f, 2f)).coerceIn(0f, minCornerRadiusPx.coerceAtLeast(0f))

  /** Library contract: refraction amount must stay within [0, size min dimension]. */
  fun refractionAmountPx(requestedPx: Float, minDimensionPx: Float, lensRefraction: Float): Float =
    (requestedPx * lensRefraction.coerceIn(0f, 2f)).coerceIn(0f, minDimensionPx.coerceAtLeast(0f))
}

/** True only where the lens engine may run (see [LensGlassPolicy.lensAllowed]). */
val LocalLensGlassEnabled = staticCompositionLocalOf { false }

/**
 * Backdrop of the current page content (recorded with `Modifier.layerBackdrop`), for glass
 * that floats above ordinary Compose content (nav pill, mini player, popups, settings). Null
 * when no host recorded one; callers then fall back to the frosted path.
 * Video (SurfaceView) cannot be recorded by a layer backdrop: player glass keeps using the
 * captured video frame (LocalLiquidGlassBackdrop) drawn through a custom backdrop instead.
 */
val LocalPageBackdrop = staticCompositionLocalOf<Backdrop?> { null }
