package app.infinity.mpvz.ui.preferences.components

import app.infinity.mpvz.ui.components.GlassModalBottomSheet
import app.infinity.mpvz.ui.preferences.components.settingsGlassContainerColor

import app.infinity.mpvz.ui.preferences.components.settingsGlassSurface

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.AlertDialog
import app.infinity.mpvz.ui.preferences.components.ThemeGlassButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.infinity.mpvz.ui.preferences.components.ThemeGlassOutlinedButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import app.infinity.mpvz.R
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.CustomThemeData
import app.infinity.mpvz.preferences.copyThemeMedia
import app.infinity.mpvz.preferences.sampleThemeColors
import app.infinity.mpvz.preferences.sampleMediaAspectRatio
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.presentation.components.TintedSlider
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
    Text(stringResource(R.string.pref_custom_theme_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.pref_custom_theme_summary), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
    themes.firstOrNull { it.id == activeId }?.let { selected ->
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(stringResource(R.string.pref_custom_theme_selected, selected.name), style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { editingExisting = true; draft = selected }) {
          Icon(Icons.RoundedFilled.Edit, contentDescription = stringResource(R.string.pref_custom_theme_edit))
        }
        IconButton(onClick = {
          preferences.customThemes.set(themes.filterNot { it.id == selected.id })
          preferences.activeCustomThemeId.set("")
          scope.launch(Dispatchers.IO) { runCatching { File(selected.mediaPath).delete() } }
        }) { Icon(Icons.RoundedFilled.Delete, contentDescription = stringResource(R.string.pref_custom_theme_delete), tint = MaterialTheme.colorScheme.error) }
      }
    }
    Button(onClick = { picker.launch(arrayOf("image/*", "video/*")) }, modifier = Modifier.fillMaxWidth()) {
      Text(stringResource(R.string.pref_custom_theme_add))
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
internal fun CustomThemeEditor(
  initial: CustomThemeData,
  isNew: Boolean,
  onDismiss: () -> Unit,
  onSave: (CustomThemeData) -> Unit,
) {
  var name by remember(initial.id) { mutableStateOf(initial.name) }
  var overlay by remember(initial.id) { mutableStateOf(initial.overlay.coerceIn(0f, 0.65f)) }
  var blur by remember(initial.id) { mutableStateOf(initial.blur.coerceIn(0f, 24f)) }
  var brightness by remember(initial.id) { mutableStateOf(initial.brightness) }
  var saturation by remember(initial.id) { mutableStateOf(initial.saturation) }
  var visibility by remember(initial.id) { mutableStateOf(initial.visibility) }
  var surfaceOpacity by remember(initial.id) { mutableStateOf(initial.surfaceOpacity.coerceIn(0.55f, 1f)) }
  var scale by remember(initial.id) { mutableStateOf(initial.scale) }
  var offsetX by remember(initial.id) { mutableStateOf(initial.offsetX) }
  var offsetY by remember(initial.id) { mutableStateOf(initial.offsetY) }
  var fitMode by remember(initial.id) { mutableStateOf(initial.fitMode) }
  var aspectMode by remember(initial.id) { mutableStateOf(initial.aspectMode) }
  var muted by remember(initial.id) { mutableStateOf(initial.muted) }
  var textColorHex by remember(initial.id) { mutableStateOf("#%08X".format(initial.onBackgroundArgb)) }
  var textHue by remember(initial.id) { mutableStateOf(0f) }
  var textSaturation by remember(initial.id) { mutableStateOf(1f) }
  var textValue by remember(initial.id) { mutableStateOf(1f) }
  var textAlpha by remember(initial.id) { mutableStateOf(AndroidColor.alpha(initial.onBackgroundArgb) / 255f) }
  var showEditor by remember(initial.id) { mutableStateOf(false) }
  val textColorArgb = runCatching { AndroidColor.parseColor(textColorHex.trim().let { if (it.startsWith("#")) it else "#$it" }) }.getOrNull()
  fun updateTextColorFromSliders() {
    val hsv = floatArrayOf(textHue, textSaturation, textValue)
    val argb = AndroidColor.HSVToColor((textAlpha * 255f).toInt().coerceIn(0, 255), hsv)
    textColorHex = "#%08X".format(argb)
  }
  val edited = initial.copy(name = name, overlay = overlay, blur = blur, brightness = brightness, saturation = saturation, visibility = visibility, surfaceOpacity = surfaceOpacity, scale = scale, offsetX = offsetX, offsetY = offsetY, fitMode = fitMode, aspectMode = aspectMode, muted = muted, onBackgroundArgb = textColorArgb ?: initial.onBackgroundArgb)
  fun resetVisualSettings() {
    overlay = initial.overlay.coerceIn(0f, 0.65f)
    blur = initial.blur.coerceIn(0f, 24f)
    brightness = initial.brightness
    saturation = initial.saturation
    visibility = initial.visibility
    surfaceOpacity = initial.surfaceOpacity.coerceIn(0.55f, 1f)
    scale = initial.scale
    offsetX = initial.offsetX
    offsetY = initial.offsetY
    fitMode = initial.fitMode
    aspectMode = initial.aspectMode
    muted = initial.muted
    textColorHex = "#%08X".format(initial.onBackgroundArgb)
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(initial.onBackgroundArgb, hsv)
    textHue = hsv[0]
    textSaturation = hsv[1]
    textValue = hsv[2]
    textAlpha = AndroidColor.alpha(initial.onBackgroundArgb) / 255f
  }
  GlassModalBottomSheet(
      paneModifier = androidx.compose.ui.Modifier.settingsGlassSurface(cornerRadius = 28.dp, independent = true),onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerLow),) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight()
        .imePadding()

        .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
      Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
      Text(stringResource(if (isNew) R.string.pref_custom_theme_create else R.string.pref_custom_theme_edit_title), style = MaterialTheme.typography.titleLarge)
      Text(stringResource(R.string.pref_custom_theme_preview), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
      // The preview fills the sheet width. Its height is derived from the
      // phone viewport ratio, so there is no unexplained side box or distortion.
      Box(modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
          ThemeMediaPreview(
            theme = edited,
            modifier = Modifier.width(150.dp).aspectRatio(320f / 693f),
            onTransform = { zoom, panX, panY, focusX, focusY ->
              val oldScale = scale
              val newScale = (oldScale * zoom).coerceIn(0.5f, 4f)
              val effectiveZoom = newScale / oldScale
              scale = newScale
              // Keep the media point below the pinch centroid fixed while
              // scaling, then apply the user's pan in viewport coordinates.
              offsetX = (offsetX + (offsetX + focusX) / effectiveZoom - offsetX + panX - focusX).coerceIn(-1f, 1f)
              offsetY = (offsetY + (offsetY + focusY) / effectiveZoom - offsetY + panY - focusY).coerceIn(-1f, 1f)
            },
        )
      }
      Button(onClick = { showEditor = !showEditor }, modifier = Modifier.fillMaxWidth()) {
        Icon(if (showEditor) Icons.RoundedFilled.Edit else Icons.RoundedFilled.Tune, contentDescription = null)
        Text(stringResource(if (showEditor) R.string.pref_custom_theme_hide_options else R.string.pref_custom_theme_edit))
      }
        if (showEditor) {
          Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.pref_custom_theme_edit_background), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.pref_custom_theme_media_framing), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (fitMode == "crop") Button(onClick = { fitMode = "crop" }) { Text(stringResource(R.string.pref_custom_theme_crop)) } else OutlinedButton(onClick = { fitMode = "crop" }) { Text(stringResource(R.string.pref_custom_theme_crop)) }
          if (fitMode == "fit") Button(onClick = { fitMode = "fit" }) { Text(stringResource(R.string.pref_custom_theme_fit)) } else OutlinedButton(onClick = { fitMode = "fit" }) { Text(stringResource(R.string.pref_custom_theme_fit)) }
          if (fitMode == "fill") Button(onClick = { fitMode = "fill" }) { Text(stringResource(R.string.pref_custom_theme_fill)) } else OutlinedButton(onClick = { fitMode = "fill" }) { Text(stringResource(R.string.pref_custom_theme_fill)) }
        }
        if (initial.isVideo) {
          Text(stringResource(R.string.pref_custom_theme_video_aspect), style = MaterialTheme.typography.labelLarge)
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (aspectMode == "screen") Button(onClick = { aspectMode = "screen" }) { Text(stringResource(R.string.pref_custom_theme_fill_screen)) } else OutlinedButton(onClick = { aspectMode = "screen" }) { Text(stringResource(R.string.pref_custom_theme_fill_screen)) }
            if (aspectMode == "source") Button(onClick = { aspectMode = "source" }) { Text(stringResource(R.string.pref_custom_theme_keep_source)) } else OutlinedButton(onClick = { aspectMode = "source" }) { Text(stringResource(R.string.pref_custom_theme_keep_source)) }
          }
        }
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_zoom_scale), scale, 0.5f..2.5f, { scale = it })
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_horizontal_position), offsetX, -1f..1f, { offsetX = it })
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_vertical_position), offsetY, -1f..1f, { offsetY = it })
        Text(stringResource(R.string.pref_custom_theme_media_appearance), style = MaterialTheme.typography.titleMedium)
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_blur), blur, 0f..24f, { blur = it })
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_brightness), brightness, 0.25f..2f, { brightness = it })
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_saturation), saturation, 0f..2f, { saturation = it })
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_transparency), visibility, 0.15f..1f, { visibility = it })
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_overlay), overlay, 0f..0.65f, { overlay = it })
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_panel_opacity), surfaceOpacity, 0.55f..1f, { surfaceOpacity = it })
        Text(stringResource(R.string.pref_custom_theme_text_colour), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          Box(Modifier.size(34.dp).clip(MaterialTheme.shapes.small).background(androidx.compose.ui.graphics.Color(textColorArgb ?: initial.onBackgroundArgb)))
          OutlinedTextField(
            value = textColorHex,
            onValueChange = {
              textColorHex = it.take(9)
              runCatching { AndroidColor.parseColor(textColorHex.trim().let { value -> if (value.startsWith("#")) value else "#$value" }) }.onSuccess { argb ->
                val hsv = FloatArray(3)
                AndroidColor.colorToHSV(argb, hsv)
                textHue = hsv[0]
                textSaturation = hsv[1]
                textValue = hsv[2]
                textAlpha = AndroidColor.alpha(argb) / 255f
              }
            },
            label = { Text(stringResource(R.string.pref_custom_theme_argb_hex)) },
            placeholder = { Text(stringResource(R.string.pref_custom_theme_argb_placeholder)) },
            singleLine = true,
            isError = textColorArgb == null,
            modifier = Modifier.weight(1f),
          )
        }
        Text(stringResource(R.string.pref_custom_theme_colour_sliders), style = MaterialTheme.typography.labelLarge)
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_hue, textHue.toInt()), textHue, 0f..360f, { textHue = it; updateTextColorFromSliders() }, androidx.compose.ui.graphics.Color(textColorArgb ?: initial.onBackgroundArgb))
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_saturation_value, (textSaturation * 100).toInt()), textSaturation, 0f..1f, { textSaturation = it; updateTextColorFromSliders() }, androidx.compose.ui.graphics.Color(textColorArgb ?: initial.onBackgroundArgb))
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_brightness_value, (textValue * 100).toInt()), textValue, 0f..1f, { textValue = it; updateTextColorFromSliders() }, androidx.compose.ui.graphics.Color(textColorArgb ?: initial.onBackgroundArgb))
        ThemeAdjustmentSlider(stringResource(R.string.pref_custom_theme_opacity_value, (textAlpha * 100).toInt()), textAlpha, 0f..1f, { textAlpha = it; updateTextColorFromSliders() }, androidx.compose.ui.graphics.Color(textColorArgb ?: initial.onBackgroundArgb))
        if (initial.isVideo) {
          SwitchPreference(
            value = muted,
            onValueChange = { muted = it },
            title = { Text(stringResource(R.string.pref_custom_theme_mute_video)) },
          )
        }
          }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.pref_custom_theme_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
      }
      val density = LocalDensity.current
      val imeVisible = WindowInsets.ime.getBottom(density) > 0
      val navigationBarDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp().value }
      BoxWithConstraints(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 8.dp)
          .padding(bottom = ThemeEditorLayoutPolicy.bottomSpacingDp(navigationBarDp, imeVisible).dp),
      ) {
        val buttonModifier = Modifier
          .heightIn(min = 40.dp)
          .weight(1f)
        // Compact in-row actions on every width. The stacked full-width trio was
        // taking a third of the editor on phones.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = { resetVisualSettings() },
            modifier = buttonModifier,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
          ) {
            Icon(Icons.RoundedFilled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.generic_reset), maxLines = 1)
          }
          OutlinedButton(
            onClick = onDismiss,
            modifier = buttonModifier,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
          ) {
            Text(stringResource(R.string.generic_cancel), maxLines = 1)
          }
          Button(
            enabled = name.isNotBlank(),
            onClick = { onSave(edited.copy(name = name.trim())) },
            modifier = buttonModifier,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
          ) {
            Text(stringResource(R.string.generic_save), maxLines = 1)
          }
        }
      }
    }
  }
}

@Composable
private fun ThemeAdjustmentSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  onValueChange: (Float) -> Unit,
  tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    TintedSlider(
      value = value,
      onValueChange = onValueChange,
      valueRange = range,
      tint = tint,
    )
  }
}

@Composable
private fun ThemeMediaPreview(theme: CustomThemeData, modifier: Modifier = Modifier, onTransform: (Float, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> }) {
  Box(modifier = modifier.clip(MaterialTheme.shapes.large).background(androidx.compose.ui.graphics.Color.Black).pointerInput(theme.id) {
    detectTransformGestures { centroid, pan, zoom, _ ->
      val focusX = ((centroid.x / size.width.toFloat()) * 2f - 1f).coerceIn(-1f, 1f)
      val focusY = ((centroid.y / size.height.toFloat()) * 2f - 1f).coerceIn(-1f, 1f)
      onTransform(zoom, pan.x / size.width.coerceAtLeast(1), pan.y / size.height.coerceAtLeast(1), focusX, focusY)
    }
  }) {
    if (theme.isVideo) {
          AndroidView(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = theme.visibility }, factory = { context -> CustomThemeVideoView(context).also { it.applyTheme(theme) } }, update = { view -> view.updateThemeEffects(theme) })
    } else {
      val bitmap = remember(theme.mediaPath) { android.graphics.BitmapFactory.decodeFile(theme.mediaPath) }
      bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = theme.contentScale(), alignment = androidx.compose.ui.BiasAlignment(theme.offsetX, theme.offsetY), colorFilter = theme.mediaColorFilter(), modifier = Modifier.fillMaxSize().blur(theme.blur.dp).graphicsLayer(scaleX = theme.scale, scaleY = theme.scale, alpha = theme.visibility)) }
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
  // Fill means fill the viewport by cropping, never distort the source.
  "fill" -> ContentScale.Crop
  else -> ContentScale.Crop
}
