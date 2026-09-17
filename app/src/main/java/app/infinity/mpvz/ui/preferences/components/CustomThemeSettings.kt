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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
    themes.forEach { theme ->
      Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          RadioButton(selected = activeId == theme.id, onClick = { preferences.activeCustomThemeId.set(theme.id) })
          Column(modifier = Modifier.weight(1f)) {
            Text(theme.name, style = MaterialTheme.typography.titleSmall)
            Text(if (theme.isVideo) "Video theme" else "Photo theme", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
          }
          OutlinedButton(onClick = { editingExisting = true; draft = theme }) { Text("Edit") }
          IconButton(onClick = {
            val remaining = themes.filterNot { it.id == theme.id }
            preferences.customThemes.set(remaining)
            if (activeId == theme.id) preferences.activeCustomThemeId.set("")
            scope.launch(Dispatchers.IO) { runCatching { File(theme.mediaPath).delete() } }
          }) { Text("×", style = MaterialTheme.typography.titleLarge) }
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
  var overlay by remember(initial.id) { mutableStateOf(initial.overlay) }
  var brightness by remember(initial.id) { mutableStateOf(initial.brightness) }
  var saturation by remember(initial.id) { mutableStateOf(initial.saturation) }
  var muted by remember(initial.id) { mutableStateOf(initial.muted) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (isNew) "Create custom theme" else "Edit custom theme") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMediaPreview(
          theme = initial.copy(name = name, overlay = overlay, brightness = brightness, saturation = saturation, muted = muted),
        )
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Theme name") }, singleLine = true)
        Text("Background dimming")
        Slider(value = overlay, onValueChange = { overlay = it }, valueRange = 0.1f..0.9f)
        Text("Brightness")
        Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0.5f..1.5f)
        Text("Saturation")
        Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..2f)
        if (initial.isVideo) {
          Row { Checkbox(checked = muted, onCheckedChange = { muted = it }); Text("Mute video theme") }
        }
      }
    },
    confirmButton = {
      Button(enabled = name.isNotBlank(), onClick = {
        onSave(initial.copy(name = name.trim(), overlay = overlay, brightness = brightness, saturation = saturation, muted = muted))
      }) { Text("Save") }
    },
    dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
  )
}


@Composable
private fun ThemeMediaPreview(theme: CustomThemeData) {
  Box(modifier = Modifier.fillMaxWidth().size(180.dp)) {
    if (theme.isVideo) {
      AndroidView(
        modifier = Modifier.fillMaxWidth().size(180.dp),
        factory = { context ->
          VideoView(context).apply {
            setVideoPath(theme.mediaPath)
            setOnPreparedListener { player ->
              player.isLooping = theme.loopVideo
              player.setVolume(if (theme.muted) 0f else 1f, if (theme.muted) 0f else 1f)
              start()
            }
          }
        },
        update = { view -> view.setOnPreparedListener { player ->
          player.isLooping = theme.loopVideo
          player.setVolume(if (theme.muted) 0f else 1f, if (theme.muted) 0f else 1f)
          if (!player.isPlaying) view.start()
        } },
      )
    } else {
      val bitmap = remember(theme.mediaPath) { android.graphics.BitmapFactory.decodeFile(theme.mediaPath) }
      bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().size(180.dp)) }
    }
    Box(Modifier.fillMaxWidth().size(180.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = theme.overlay.coerceIn(0f, 0.92f))))
  }
}
