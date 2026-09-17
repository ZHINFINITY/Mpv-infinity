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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.infinity.mpvz.preferences.preference.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.VideoView
import java.io.File

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
        )
      }
      editingExisting = false
      draft = created
    }
  }

  Column(modifier = modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Custom themes", style = MaterialTheme.typography.titleMedium)
    Text("Save a photo or video theme and adjust it later.", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
    if (themes.isNotEmpty()) {
      Text("Saved themes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      items(themes, key = { it.id }) { theme ->
        Card(modifier = Modifier.width(190.dp), border = if (activeId == theme.id) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
          Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(modifier = Modifier.fillMaxWidth().height(92.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
              if (!theme.isVideo) {
                val bitmap = remember(theme.mediaPath) { android.graphics.BitmapFactory.decodeFile(theme.mediaPath) }
                bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
              } else {
                Text("VIDEO", modifier = Modifier.align(androidx.compose.ui.Alignment.Center), style = MaterialTheme.typography.labelLarge)
              }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
              RadioButton(selected = activeId == theme.id, onClick = { preferences.activeCustomThemeId.set(theme.id) })
              Column(modifier = Modifier.weight(1f)) {
                Text(theme.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(if (theme.isVideo) "Video theme" else "Photo theme", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
              }
            }
            Row {
              OutlinedButton(onClick = { editingExisting = true; draft = theme }, modifier = Modifier.weight(1f)) { Text("Edit") }
              IconButton(onClick = {
                val remaining = themes.filterNot { it.id == theme.id }
                preferences.customThemes.set(remaining)
                if (activeId == theme.id) preferences.activeCustomThemeId.set("")
                scope.launch(Dispatchers.IO) { runCatching { File(theme.mediaPath).delete() } }
              }) { Text("×", style = MaterialTheme.typography.titleLarge) }
            }
          }
        }
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
  var scale by remember(initial.id) { mutableStateOf(initial.scale) }
  var offsetX by remember(initial.id) { mutableStateOf(initial.offsetX) }
  var offsetY by remember(initial.id) { mutableStateOf(initial.offsetY) }
  var fitMode by remember(initial.id) { mutableStateOf(initial.fitMode) }
  var muted by remember(initial.id) { mutableStateOf(initial.muted) }
  val edited = initial.copy(name = name, overlay = overlay, brightness = brightness, saturation = saturation, scale = scale, offsetX = offsetX, offsetY = offsetY, fitMode = fitMode, muted = muted)
  androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle() }) {
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 20.dp, vertical = 8.dp)) {
      Text(if (isNew) "Create custom theme" else "Edit custom theme", style = MaterialTheme.typography.titleLarge)
      Text("Live preview", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
      ThemeMediaPreview(theme = edited, modifier = Modifier.fillMaxWidth().height(220.dp))
      Column(modifier = Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Theme name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Media framing", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Button(onClick = { fitMode = "crop" }) { Text("Crop") }; OutlinedButton(onClick = { fitMode = "fit" }) { Text("Fit") }; OutlinedButton(onClick = { fitMode = "fill" }) { Text("Fill") } }
        Text("Zoom / scale"); Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.5f..2.5f)
        Text("Horizontal position"); Slider(value = offsetX, onValueChange = { offsetX = it }, valueRange = -1f..1f)
        Text("Vertical position"); Slider(value = offsetY, onValueChange = { offsetY = it }, valueRange = -1f..1f)
        Text("Media appearance", style = MaterialTheme.typography.titleMedium)
        Text("Brightness"); Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0.25f..2f)
        Text("Saturation"); Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..2f)
        Text("Dim overlay (lower shows more media)"); Slider(value = overlay, onValueChange = { overlay = it }, valueRange = 0f..0.65f)
        if (initial.isVideo) Row { Checkbox(checked = muted, onCheckedChange = { muted = it }); Text("Mute video theme") }
      }
      Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) { OutlinedButton(onClick = onDismiss) { Text("Cancel") }; Button(enabled = name.isNotBlank(), onClick = { onSave(edited.copy(name = name.trim())) }, modifier = Modifier.padding(start = 8.dp)) { Text("Save theme") } }
    }
  }
}

@Composable
private fun ThemeMediaPreview(theme: CustomThemeData, modifier: Modifier = Modifier) {
  Box(modifier = modifier.clip(MaterialTheme.shapes.large).background(androidx.compose.ui.graphics.Color.Black)) {
    if (theme.isVideo) {
      AndroidView(modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = theme.scale * if (theme.fitMode == "crop") 1.45f else 1f; scaleY = theme.scale * if (theme.fitMode == "crop") 1.45f else 1f; translationX = theme.offsetX * size.width * 0.5f; translationY = theme.offsetY * size.height * 0.5f }, factory = { context ->
        VideoView(context).apply {
          setVideoPath(theme.mediaPath)
          setOnPreparedListener { player ->
            player.isLooping = theme.loopVideo
            player.setVolume(if (theme.muted) 0f else 1f, if (theme.muted) 0f else 1f)
            start()
          }
        }
      }, update = { view -> view.setOnPreparedListener { player ->
        player.isLooping = theme.loopVideo
        player.setVolume(if (theme.muted) 0f else 1f, if (theme.muted) 0f else 1f)
        if (!player.isPlaying) view.start()
      } })
    } else {
      val bitmap = remember(theme.mediaPath) { android.graphics.BitmapFactory.decodeFile(theme.mediaPath) }
      bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = theme.contentScale(), alignment = androidx.compose.ui.BiasAlignment(theme.offsetX, theme.offsetY), colorFilter = theme.mediaColorFilter(), modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = theme.scale, scaleY = theme.scale)) }
    }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = (theme.overlay * 0.35f).coerceIn(0f, 0.35f))))
  }
}

private fun CustomThemeData.mediaColorFilter(): ColorFilter {
  val matrix = ColorMatrix()
  matrix.setToSaturation(saturation)
  matrix.scale(brightness, brightness, brightness, 1f)
  return ColorFilter.colorMatrix(matrix)
}

private fun CustomThemeData.contentScale(): ContentScale = when (fitMode) {
  "fit" -> ContentScale.Fit
  "fill" -> ContentScale.FillBounds
  else -> ContentScale.Crop
}
