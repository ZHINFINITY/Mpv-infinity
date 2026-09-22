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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

/** Video renderer that preserves source proportions while supporting theme effects. */
class CustomThemeVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, DefaultLifecycleObserver {
  private var player: MediaPlayer? = null
  private var surface: Surface? = null
  private var lifecycleOwner: LifecycleOwner? = null
  private var lifecycleStarted = false
  private var path: String = ""
  private var loop = true
  private var muted = true
  private var brightness = 1f
  private var saturation = 1f
  private var blur = 0f
  private var mediaAspectRatio = 1f
  private var fitMode = "crop"
  private var aspectMode = "screen"
  private var mediaScale = 1f
  private var offsetX = 0f
  private var offsetY = 0f
  private var retryGeneration = 0

  init { surfaceTextureListener = this }

  fun configure(
    path: String,
    loop: Boolean,
    muted: Boolean,
    brightness: Float,
    saturation: Float,
    blur: Float,
    visibility: Float,
    mediaAspectRatio: Float = 1f,
    fitMode: String = "crop",
    aspectMode: String = "screen",
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
  ) {
    val changed = this.path != path
    val muteChanged = this.muted != muted
    val effectsChanged = this.brightness != brightness || this.saturation != saturation || this.blur != blur
    val transformChanged = this.mediaAspectRatio != mediaAspectRatio || this.fitMode != fitMode || this.aspectMode != aspectMode || this.mediaScale != scale || this.offsetX != offsetX || this.offsetY != offsetY
    this.path = path
    this.loop = loop
    this.muted = muted
    this.brightness = brightness
    this.saturation = saturation
    this.blur = blur.coerceIn(0f, 24f)
    this.mediaAspectRatio = mediaAspectRatio.coerceIn(0.05f, 20f)
    this.fitMode = fitMode
    this.aspectMode = aspectMode
    this.mediaScale = scale.coerceIn(0.5f, 4f)
    this.offsetX = offsetX.coerceIn(-1f, 1f)
    this.offsetY = offsetY.coerceIn(-1f, 1f)
    alpha = visibility.coerceIn(0.15f, 1f)
    if (changed) releasePlayer()
    if (muteChanged) {
      runCatching { player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) }
    }
    if (isAvailable) startIfReady()
    if (effectsChanged || changed) applyEffects()
    if (transformChanged || changed) applyAspectTransform()
  }

  private fun startIfReady() {
    if (!lifecycleStarted || player != null || path.isBlank() || surface == null) return
    val generation = retryGeneration
    player = MediaPlayer().apply {
      setSurface(surface)
      isLooping = loop
      setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
      setOnPreparedListener { preparedPlayer ->
        // prepareAsync can finish after the view has been reconfigured or detached.
        // Never start a stale decoder; doing so can freeze the new surface and cause
        // a visible restart/stutter when custom themes are edited or recomposed.
        if (generation == retryGeneration && player === preparedPlayer && isAvailable) {
          preparedPlayer.start()
        }
      }
      // MediaPlayer's native looping path avoids the seek/restart hitch at the loop boundary.
      setOnErrorListener { _, _, _ ->
        // Do not leave a failed player displaying its last frame. Recreate it
        // once after a short backoff so transient decoder errors recover
        // without a rapid restart loop that causes stutter.
        releasePlayer()
        val generation = retryGeneration
        postDelayed({
          if (generation == retryGeneration && isAvailable) {
            startIfReady()
          }
        }, 1000L)
        true
      }
      runCatching { setDataSource(path); prepareAsync() }.onFailure { releasePlayer() }
    }
  }

  private fun applyEffects() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val matrix = ColorMatrix().apply { setSaturation(saturation.coerceIn(0f, 2f)) }
      val values = matrix.array.copyOf()
      val b = brightness.coerceIn(0.25f, 2f)
      for (index in intArrayOf(0, 1, 2, 4, 5, 6, 7, 9, 10, 11, 12, 14)) values[index] *= b
      val colorEffect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix(values)))
      val effect = if (blur > 0f) {
        RenderEffect.createChainEffect(
          colorEffect,
          RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP),
        )
      } else colorEffect
      runCatching { setRenderEffect(effect) }
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
    retryGeneration++
    player?.runCatching { stop() }
    player?.release()
    player = null
  }

  private fun bindLifecycleOwner() {
    val owner = findViewTreeLifecycleOwner() ?: return
    if (lifecycleOwner === owner) return
    lifecycleOwner?.lifecycle?.removeObserver(this)
    lifecycleOwner = owner
    owner.lifecycle.addObserver(this)
    lifecycleStarted = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    if (lifecycleStarted) startIfReady()
  }

  override fun onResume(owner: LifecycleOwner) {
    lifecycleStarted = true
    startIfReady()
  }

  override fun onPause(owner: LifecycleOwner) {
    lifecycleStarted = false
    releasePlayer()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    bindLifecycleOwner()
    if (lifecycleOwner == null) post { bindLifecycleOwner() }
  }

  override fun onDetachedFromWindow() {
    lifecycleStarted = false
    releasePlayer()
    lifecycleOwner?.lifecycle?.removeObserver(this)
    lifecycleOwner = null
    super.onDetachedFromWindow()
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
  blur = theme.blur,
  visibility = theme.visibility,
  mediaAspectRatio = theme.mediaAspectRatio,
  fitMode = theme.fitMode,
  aspectMode = theme.aspectMode,
  scale = theme.scale,
  offsetX = theme.offsetX,
  offsetY = theme.offsetY,
)

fun CustomThemeVideoView.updateThemeEffects(theme: CustomThemeData) {
  configure(theme.mediaPath, theme.loopVideo, theme.muted, theme.brightness, theme.saturation, theme.blur, theme.visibility, theme.mediaAspectRatio, theme.fitMode, theme.aspectMode, theme.scale, theme.offsetX, theme.offsetY)
}
