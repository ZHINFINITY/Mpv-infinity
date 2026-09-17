package app.infinity.mpvz.preferences

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView

/** Video renderer that preserves source proportions while supporting theme effects. */
class CustomThemeVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
  private var player: MediaPlayer? = null
  private var surface: Surface? = null
  private var path: String = ""
  private var loop = true
  private var muted = true
  private var brightness = 1f
  private var saturation = 1f
  private var mediaAspectRatio = 1f
  private var fitMode = "crop"
  private var aspectMode = "screen"
  private var mediaScale = 1f
  private var offsetX = 0f
  private var offsetY = 0f

  init { surfaceTextureListener = this }

  fun configure(
    path: String,
    loop: Boolean,
    muted: Boolean,
    brightness: Float,
    saturation: Float,
    visibility: Float,
    mediaAspectRatio: Float = 1f,
    fitMode: String = "crop",
    aspectMode: String = "screen",
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
  ) {
    val changed = this.path != path
    this.path = path
    this.loop = loop
    this.muted = muted
    this.brightness = brightness
    this.saturation = saturation
    this.mediaAspectRatio = mediaAspectRatio.coerceIn(0.05f, 20f)
    this.fitMode = fitMode
    this.aspectMode = aspectMode
    this.mediaScale = scale.coerceIn(0.5f, 4f)
    this.offsetX = offsetX.coerceIn(-1f, 1f)
    this.offsetY = offsetY.coerceIn(-1f, 1f)
    alpha = visibility.coerceIn(0.15f, 1f)
    if (changed) releasePlayer()
    if (isAvailable) startIfReady()
    applyEffects()
    applyAspectTransform()
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
      runCatching { setRenderEffect(RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix(values)))) }
    }
  }

  /** TextureView otherwise stretches every source frame to its view bounds. */
  private fun applyAspectTransform() {
    if (width <= 0 || height <= 0) return
    val viewAspect = width.toFloat() / height.toFloat()
    val sourceAspect = mediaAspectRatio.coerceIn(0.05f, 20f)
    val preserveSource = aspectMode == "source" || fitMode == "fit"
    val crop = !preserveSource
    val xCorrection: Float
    val yCorrection: Float
    if (sourceAspect >= viewAspect) {
      xCorrection = if (crop) sourceAspect / viewAspect else 1f
      yCorrection = if (crop) 1f else viewAspect / sourceAspect
    } else {
      xCorrection = if (crop) 1f else sourceAspect / viewAspect
      yCorrection = if (crop) viewAspect / sourceAspect else 1f
    }
    val matrix = Matrix()
    matrix.setScale(xCorrection * mediaScale, yCorrection * mediaScale, width / 2f, height / 2f)
    matrix.postTranslate(offsetX * width * 0.5f, offsetY * height * 0.5f)
    setTransform(matrix)
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
    applyAspectTransform()
  }

  override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
    applyAspectTransform()
  }
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
  mediaAspectRatio = theme.mediaAspectRatio,
  fitMode = theme.fitMode,
  aspectMode = theme.aspectMode,
  scale = theme.scale,
  offsetX = theme.offsetX,
  offsetY = theme.offsetY,
)

fun CustomThemeVideoView.updateThemeEffects(theme: CustomThemeData) {
  configure(theme.mediaPath, theme.loopVideo, theme.muted, theme.brightness, theme.saturation, theme.visibility, theme.mediaAspectRatio, theme.fitMode, theme.aspectMode, theme.scale, theme.offsetX, theme.offsetY)
}
