package app.infinity.mpvz.ui.preferences.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.CustomThemeData
import app.infinity.mpvz.preferences.copyThemeMedia
import app.infinity.mpvz.preferences.sampleThemeColors
import app.infinity.mpvz.preferences.sampleMediaAspectRatio
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import app.infinity.mpvz.preferences.CustomThemeVideoView
import app.infinity.mpvz.preferences.applyTheme
import app.infinity.mpvz.preferences.updateThemeEffects

@Composable
fun CustomThemeSettings(
  preferences: AppearancePreferences,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val themes by preferences.customThemes.collectAsState()
  val activeId by preferences.activeCustomThemeId.collectAsState()
  var draft by remember { mutableStateOf<CustomThemeData?>(null) }
  var editingExisting by remember { mutableStateOf(false) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      val created = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri).orEmpty()
        val isVideo = type.startsWith("video/")
        val file = copyThemeMedia(context, uri, isVideo)
        val (primary, background, onBackground) = sampleThemeColors(file, isVideo)
        CustomThemeData(
          name = "My theme",
          mediaPath = file.absolutePath,
          isVideo = isVideo,
          primaryArgb = primary,
          backgroundArgb = background,
          onBackgroundArgb = onBackground,
          mediaAspectRatio = sampleMediaAspectRatio(file, isVideo),
        )
      }
      editingExisting = false
      draft = created
    }
  }

  Column(modifier = modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Custom themes", style = MaterialTheme.typography.titleMedium)
    Text("Save a photo or video theme and adjust it later.", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
    themes.firstOrNull { it.id == activeId }?.let { selected ->
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Selected in theme rail: ${selected.name}", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { editingExisting = true; draft = selected }) {
          Icon(Icons.RoundedFilled.Edit, contentDescription = "Edit theme")
        }
        IconButton(onClick = {
          preferences.customThemes.set(themes.filterNot { it.id == selected.id })
          preferences.activeCustomThemeId.set("")
          scope.launch(Dispatchers.IO) { runCatching { File(selected.mediaPath).delete() } }
        }) { Icon(Icons.RoundedFilled.Delete, contentDescription = "Delete theme", tint = MaterialTheme.colorScheme.error) }
      }
    }
    Button(onClick = { picker.launch(arrayOf("image/*", "video/*")) }, modifier = Modifier.fillMaxWidth()) {
      Text("Add photo or video theme")
    }
  }

  draft?.let { value ->
    CustomThemeEditor(
      initial = value,
      isNew = !editingExisting,
      onDismiss = { draft = null },
      onSave = { edited ->
        val updated = if (editingExisting) themes.map { if (it.id == edited.id) edited else it } else themes + edited
        preferences.customThemes.set(updated)
        preferences.activeCustomThemeId.set(edited.id)
        draft = null
      },
    )
  }
}

@Composable
private fun CustomThemeEditor(
  initial: CustomThemeData,
  isNew: Boolean,
  onDismiss: () -> Unit,
  onSave: (CustomThemeData) -> Unit,
) {
  var name by remember(initial.id) { mutableStateOf(initial.name) }
  var overlay by remember(initial.id) { mutableStateOf(initial.overlay.coerceIn(0f, 0.65f)) }
  var brightness by remember(initial.id) { mutableStateOf(initial.brightness) }
  var saturation by remember(initial.id) { mutableStateOf(initial.saturation) }
  var visibility by remember(initial.id) { mutableStateOf(initial.visibility) }
  var scale by remember(initial.id) { mutableStateOf(initial.scale) }
  var offsetX by remember(initial.id) { mutableStateOf(initial.offsetX) }
  var offsetY by remember(initial.id) { mutableStateOf(initial.offsetY) }
  var fitMode by remember(initial.id) { mutableStateOf(initial.fitMode) }
  var aspectMode by remember(initial.id) { mutableStateOf(initial.aspectMode) }
  var muted by remember(initial.id) { mutableStateOf(initial.muted) }
  val edited = initial.copy(name = name, overlay = overlay, brightness = brightness, saturation = saturation, visibility = visibility, scale = scale, offsetX = offsetX, offsetY = offsetY, fitMode = fitMode, aspectMode = aspectMode, muted = muted)
  androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow, dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle() }) {
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 20.dp, vertical = 8.dp)) {
      Text(if (isNew) "Create custom theme" else "Edit custom theme", style = MaterialTheme.typography.titleLarge)
      Text("Live preview", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
      // Use a phone-shaped portrait viewport so crop/fit/position changes are
      // previewed in the same geometry users will see in the app.
        Box(modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
          ThemeMediaPreview(
            theme = edited,
            modifier = Modifier.width(150.dp).aspectRatio(320f / 693f),
            onTransform = { zoom, panX, panY ->
              scale = (scale * zoom).coerceIn(0.5f, 4f)
              offsetX = (offsetX + panX).coerceIn(-1f, 1f)
              offsetY = (offsetY + panY).coerceIn(-1f, 1f)
            },
        )
      }
      Column(modifier = Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Theme name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Media framing", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (fitMode == "crop") Button(onClick = { fitMode = "crop" }) { Text("Crop") } else OutlinedButton(onClick = { fitMode = "crop" }) { Text("Crop") }
          if (fitMode == "fit") Button(onClick = { fitMode = "fit" }) { Text("Fit") } else OutlinedButton(onClick = { fitMode = "fit" }) { Text("Fit") }
          if (fitMode == "fill") Button(onClick = { fitMode = "fill" }) { Text("Fill") } else OutlinedButton(onClick = { fitMode = "fill" }) { Text("Fill") }
        }
        if (initial.isVideo) {
          Text("Video aspect ratio", style = MaterialTheme.typography.labelLarge)
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (aspectMode == "screen") Button(onClick = { aspectMode = "screen" }) { Text("Fill phone screen") } else OutlinedButton(onClick = { aspectMode = "screen" }) { Text("Fill phone screen") }
            if (aspectMode == "source") Button(onClick = { aspectMode = "source" }) { Text("Keep source") } else OutlinedButton(onClick = { aspectMode = "source" }) { Text("Keep source") }
          }
        }
        Text("Zoom / scale"); Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.5f..2.5f)
        Text("Horizontal position"); Slider(value = offsetX, onValueChange = { offsetX = it }, valueRange = -1f..1f)
        Text("Vertical position"); Slider(value = offsetY, onValueChange = { offsetY = it }, valueRange = -1f..1f)
        Text("Media appearance", style = MaterialTheme.typography.titleMedium)
        Text("Brightness"); Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0.25f..2f)
        Text("Saturation"); Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..2f)
        Text("Background visibility"); Slider(value = visibility, onValueChange = { visibility = it }, valueRange = 0.15f..1f)
        Text("Dim overlay (lower shows more media)"); Slider(value = overlay, onValueChange = { overlay = it }, valueRange = 0f..0.65f)
        if (initial.isVideo) Row { Checkbox(checked = muted, onCheckedChange = { muted = it }); Text("Mute video theme") }
      }
      Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) { OutlinedButton(onClick = onDismiss) { Text("Cancel") }; Button(enabled = name.isNotBlank(), onClick = { onSave(edited.copy(name = name.trim())) }, modifier = Modifier.padding(start = 8.dp)) { Text("Save theme") } }
    }
  }
}

@Composable
private fun ThemeMediaPreview(theme: CustomThemeData, modifier: Modifier = Modifier, onTransform: (Float, Float, Float) -> Unit = { _, _, _ -> }) {
  Box(modifier = modifier.clip(MaterialTheme.shapes.large).background(androidx.compose.ui.graphics.Color.Black).pointerInput(theme.id) {
    detectTransformGestures { _, pan, zoom, _ -> onTransform(zoom, pan.x / 300f, pan.y / 650f) }
  }) {
    if (theme.isVideo) {
      AndroidView(modifier = Modifier.fillMaxSize().graphicsLayer { val coverScale = if (theme.fitMode == "crop" && theme.aspectMode == "screen") maxOf(1f, theme.mediaAspectRatio / (320f / 693f)) else 1f; scaleX = theme.scale * coverScale; scaleY = theme.scale * coverScale; alpha = theme.visibility; translationX = theme.offsetX * size.width * 0.5f; translationY = theme.offsetY * size.height * 0.5f }, factory = { context -> CustomThemeVideoView(context).applyTheme(theme) }, update = { view -> view.updateThemeEffects(theme) })
    } else {
      val bitmap = remember(theme.mediaPath) { android.graphics.BitmapFactory.decodeFile(theme.mediaPath) }
      bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = theme.contentScale(), alignment = androidx.compose.ui.BiasAlignment(theme.offsetX, theme.offsetY), colorFilter = theme.mediaColorFilter(), modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = theme.scale, scaleY = theme.scale, alpha = theme.visibility)) }
    }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = (theme.overlay * 0.35f).coerceIn(0f, 0.35f))))
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
  "fill" -> ContentScale.FillBounds
  else -> ContentScale.Crop
}
