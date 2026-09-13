package app.infinity.mpvz.ui.player

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * App-owned Media3 subtitle surface.
 *
 * It keeps Media3's cue timing and format decoders, while providing one stable overlay for multiple
 * simultaneous cues and user-selected typography. ASS/SSA override tags that Media3 cannot express
 * remain intentionally handled by the regular Media3 cue model until a native libass renderer is
 * linked.
 */
class MpvMedia3SubtitleView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : SubtitleView(context, attrs) {
  private var configuredTypeface: Typeface? = null

  init {
    setApplyEmbeddedStyles(false)
    setApplyEmbeddedFontSizes(false)
    setBottomPaddingFraction(0f)
    isFocusable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  fun applyMpvStyle(style: CaptionStyleCompat, fractionalTextSize: Float, scale: Float, yOffset: Float) {
    setApplyEmbeddedStyles(false)
    setApplyEmbeddedFontSizes(false)
    setStyle(style)
    setFractionalTextSize(fractionalTextSize.coerceIn(0.01f, 0.16f))
    pivotX = width / 2f
    pivotY = height.toFloat()
    scaleX = scale
    scaleY = scale
    translationY = yOffset
    configuredTypeface = style.typeface
  }

  fun setMpvCues(cues: List<Cue>) {
    // SubtitleView preserves all simultaneous cues, including multiple selected tracks.
    setCues(cues)
  }

  fun clearMpvCues() {
    setCues(emptyList())
  }

  fun configuredTypeface(): Typeface? = configuredTypeface
}
