package app.infinity.mpvz.preferences

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.os.Build

/** Video renderer that supports reliable color effects through a TextureView surface. */
class CustomThemeVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
  private var player: MediaPlayer? = null
  private var surface: Surface? = null
  private var path: String = ""
  private var loop = true
  private var muted = true
  private var brightness = 1f
  private var saturation = 1f

  init { surfaceTextureListener = this }

  fun configure(
    path: String,
    loop: Boolean,
    muted: Boolean,
    brightness: Float,
    saturation: Float,
    visibility: Float,
  ) {
    val changed = this.path != path
    this.path = path
    this.loop = loop
    this.muted = muted
    this.brightness = brightness
    this.saturation = saturation
    alpha = visibility.coerceIn(0.15f, 1f)
    if (changed) releasePlayer()
    if (isAvailable) startIfReady()
    applyEffects()
  }

  private fun startIfReady() {
    if (player != null || path.isBlank() || surface == null) return
    player = MediaPlayer().apply {
      setSurface(surface)
      isLooping = loop
      setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
      setOnPreparedListener { it.start() }
      setOnCompletionListener { if (loop) it.start() }
      setOnErrorListener { _, _, _ -> true }
      runCatching { setDataSource(path); prepareAsync() }.onFailure { releasePlayer() }
    }
  }

  private fun applyEffects() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val matrix = ColorMatrix().apply { setSaturation(saturation.coerceIn(0f, 2f)) }
      val values = matrix.array.copyOf()
      val b = brightness.coerceIn(0.25f, 2f)
      for (index in intArrayOf(0, 1, 2, 4, 5, 6, 7, 9, 10, 11, 12, 14)) values[index] *= b
      runCatching {
        setRenderEffect(RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix(values))))
      }
    }
  }

  private fun releasePlayer() {
    player?.runCatching { stop() }
    player?.release()
    player = null
  }

  override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
    surface = Surface(texture)
    startIfReady()
    applyEffects()
  }

  override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit
  override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit
  override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
    releasePlayer()
    surface?.release()
    surface = null
    return true
  }
}

fun CustomThemeVideoView.applyTheme(theme: CustomThemeData) = configure(
  path = theme.mediaPath,
  loop = theme.loopVideo,
  muted = theme.muted,
  brightness = theme.brightness,
  saturation = theme.saturation,
  visibility = theme.visibility,
)

fun CustomThemeVideoView.updateThemeEffects(theme: CustomThemeData) {
  alpha = theme.visibility.coerceIn(0.15f, 1f)
  configure(theme.mediaPath, theme.loopVideo, theme.muted, theme.brightness, theme.saturation, theme.visibility)
}
