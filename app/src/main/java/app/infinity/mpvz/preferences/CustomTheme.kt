package app.infinity.mpvz.preferences

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class CustomThemeData(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val mediaPath: String,
  val isVideo: Boolean,
  val overlay: Float = 0.58f,
  val blur: Float = 0f,
  val brightness: Float = 1f,
  val saturation: Float = 1f,
  val darkText: Boolean = false,
  val loopVideo: Boolean = true,
  val muted: Boolean = true,
  val primaryArgb: Int = Color(0xFF6750A4).toArgb(),
  val backgroundArgb: Int = Color(0xFF1C1B1F).toArgb(),
  val onBackgroundArgb: Int = Color.White.toArgb(),
)

object CustomThemeCodec {
  private val json = Json { ignoreUnknownKeys = true }
  fun encode(value: List<CustomThemeData>): String = json.encodeToString(value)
  fun decode(value: String): List<CustomThemeData> = runCatching {
    json.decodeFromString<List<CustomThemeData>>(value)
  }.getOrDefault(emptyList())
}

fun CustomThemeData.mediaFile(): File = File(mediaPath)

fun copyThemeMedia(context: Context, source: android.net.Uri, isVideo: Boolean): File {
  val directory = File(context.filesDir, "custom-themes").apply { mkdirs() }
  val extension = if (isVideo) ".mp4" else ".jpg"
  val target = File(directory, "${UUID.randomUUID()}$extension")
  context.contentResolver.openInputStream(source).use { input ->
    requireNotNull(input) { "Unable to open selected media" }
    target.outputStream().use { output -> input.copyTo(output) }
  }
  return target
}

fun sampleThemeColors(file: File, isVideo: Boolean): Triple<Int, Int, Int> {
  val bitmap: Bitmap? = if (isVideo) {
    MediaMetadataRetriever().run {
      setDataSource(file.absolutePath)
      getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC).also { release() }
    }
  } else BitmapFactory.decodeFile(file.absolutePath)
  if (bitmap == null) return Triple(Color(0xFF6750A4).toArgb(), Color(0xFF1C1B1F).toArgb(), Color.White.toArgb())
  val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
  val color = scaled.getPixel(0, 0)
  if (scaled !== bitmap) scaled.recycle()
  if (bitmap.isMutable) bitmap.recycle()
  val red = android.graphics.Color.red(color)
  val green = android.graphics.Color.green(color)
  val blue = android.graphics.Color.blue(color)
  val luminance = (red * 299 + green * 587 + blue * 114) / 1000
  val background = android.graphics.Color.rgb(
    (red * 0.32f).toInt().coerceIn(0, 255),
    (green * 0.32f).toInt().coerceIn(0, 255),
    (blue * 0.32f).toInt().coerceIn(0, 255),
  )
  val primary = android.graphics.Color.rgb(red, green, blue)
  return Triple(primary, background, if (luminance > 150) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
}
