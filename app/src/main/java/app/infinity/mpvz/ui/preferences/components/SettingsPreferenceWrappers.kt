/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.preferences.components


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.player.controls.components.GlassSlider
import me.zhanghai.compose.preference.ListPreferenceType
import me.zhanghai.compose.preference.LocalPreferenceTheme
import me.zhanghai.compose.preference.Preference as LibraryPreference

/** Retain the existing preference library's row, text, icon, and click semantics. */
@Composable
fun Preference(
  title: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: (@Composable () -> Unit)? = null,
  summary: (@Composable () -> Unit)? = null,
  widgetContainer: (@Composable () -> Unit)? = null,
  onClick: (() -> Unit)? = null,
) = LibraryPreference(title, modifier.settingsGlassSurface(cornerRadius = 18.dp), enabled, icon, summary, widgetContainer, onClick)

@Composable
fun <T> ListPreference(
  value: T, onValueChange: (T) -> Unit, values: List<T>, title: @Composable () -> Unit,
  modifier: Modifier = Modifier, enabled: Boolean = true,
  icon: (@Composable () -> Unit)? = null, summary: (@Composable () -> Unit)? = null,
  type: ListPreferenceType = ListPreferenceType.ALERT_DIALOG,
  valueToText: @Composable (T) -> AnnotatedString = { AnnotatedString(it.toString()) },
  item: (@Composable (T, T, () -> Unit) -> Unit)? = null,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  val drawOption: @Composable (T) -> Unit = { option ->
    val select = { onValueChange(option); open = false }
    if (item != null) item(option, value, select)
    else if (type == ListPreferenceType.DROPDOWN_MENU) {
      DropdownMenuItem(
        text = { Text(valueToText(option)) }, onClick = select,
        modifier = if (option == value) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier,
      )
    } else GlassOptionItem(option, value, valueToText, select)
  }
  Box(Modifier.fillMaxWidth()) {
    Preference(title, modifier, enabled, icon, summary, onClick = { open = true })
    if (type == ListPreferenceType.DROPDOWN_MENU) {
      DropdownMenu(
        expanded = open, onDismissRequest = { open = false },
        modifier = Modifier.settingsGlassSurface(cornerRadius = 12.dp, independent = true), containerColor = Color.Transparent,
      ) { values.forEach { drawOption(it) } }
    }
  }
  if (open && type == ListPreferenceType.ALERT_DIALOG) {
    AlertDialog(
      onDismissRequest = { open = false }, title = title,
      modifier = Modifier.settingsGlassSurface(cornerRadius = 28.dp, independent = true), containerColor = Color.Transparent,
      text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
          items(values.size) { drawOption(values[it]) }
        }
      },
      confirmButton = {},
      dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
  }
}

@Composable
fun <T> ListPreference(
  state: MutableState<T>, values: List<T>, title: @Composable () -> Unit,
  modifier: Modifier = Modifier, enabled: Boolean = true,
  icon: (@Composable () -> Unit)? = null, summary: (@Composable () -> Unit)? = null,
  type: ListPreferenceType = ListPreferenceType.ALERT_DIALOG,
  valueToText: @Composable (T) -> AnnotatedString = { AnnotatedString(it.toString()) },
  item: (@Composable (T, T, () -> Unit) -> Unit)? = null,
) = ListPreference(state.value, { state.value = it }, values, title, modifier, enabled, icon, summary, type, valueToText, item)

@Composable
private fun <T> GlassOptionItem(value: T, current: T, valueToText: @Composable (T) -> AnnotatedString, select: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp)
      .selectable(selected = value == current, role = Role.RadioButton, onClick = select)
      .padding(horizontal = 8.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = value == current, onClick = null)
    Spacer(Modifier.width(16.dp))
    Text(valueToText(value), style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
fun SliderPreference(
  value: Float, onValueChange: (Float) -> Unit, sliderValue: Float, onSliderValueChange: (Float) -> Unit,
  title: @Composable () -> Unit, modifier: Modifier = Modifier,
  valueRange: ClosedFloatingPointRange<Float> = 0f..1f, valueSteps: Int = 0,
  enabled: Boolean = true, icon: (@Composable () -> Unit)? = null,
  summary: (@Composable () -> Unit)? = null, valueText: (@Composable () -> Unit)? = null,
) {
  var previousValue by remember { mutableFloatStateOf(value) }
  SideEffect {
    if (previousValue != value) {
      previousValue = value
      onSliderValueChange(value)
    }
  }
  Preference(title, modifier, enabled, icon, summary = {
    Column {
      summary?.invoke()
      Row(verticalAlignment = Alignment.CenterVertically) {
        // Keep drag state local: sampled-glass tuning is expensive, so persistence and
        // app-wide resampling happen once on release rather than for every pointer pixel.
        var latest by remember(sliderValue) { mutableFloatStateOf(sliderValue) }
        GlassSlider(
          value = latest, onValueChange = { latest = it },
          modifier = Modifier.weight(1f), enabled = enabled,
          valueRange = valueRange, steps = valueSteps,
          // The preference setter is the single commit point; SideEffect above
          // synchronizes the visual slider state after the store emits.
          onValueChangeFinished = { onValueChange(latest) },
        )
        if (valueText != null) Box(Modifier.padding(start = LocalPreferenceTheme.current.horizontalSpacing)) { valueText() }
      }
    }
  })
}

@Composable
fun SliderPreference(
  state: MutableState<Float>, title: @Composable () -> Unit, modifier: Modifier = Modifier,
  valueRange: ClosedFloatingPointRange<Float> = 0f..1f, valueSteps: Int = 0,
  sliderState: MutableFloatState = remember { mutableFloatStateOf(state.value) },
  enabled: Boolean = true, icon: (@Composable () -> Unit)? = null,
  summary: (@Composable () -> Unit)? = null, valueText: (@Composable () -> Unit)? = null,
) = SliderPreference(state.value, { state.value = it }, sliderState.floatValue, { sliderState.floatValue = it }, title, modifier, valueRange, valueSteps, enabled, icon, summary, valueText)

@Composable
private fun DefaultPreferenceField(value: TextFieldValue, change: (TextFieldValue) -> Unit, confirm: () -> Unit) {
  OutlinedTextField(value, change, Modifier.fillMaxWidth(), singleLine = true, keyboardActions = KeyboardActions(onDone = { confirm() }))
}

@Composable
fun <T> TextFieldPreference(
  value: T, onValueChange: (T) -> Unit, title: @Composable () -> Unit, textToValue: (String) -> T?,
  modifier: Modifier = Modifier, enabled: Boolean = true, icon: (@Composable () -> Unit)? = null,
  summary: (@Composable () -> Unit)? = null, valueToText: (T) -> String = { it.toString() },
  textField: @Composable (TextFieldValue, (TextFieldValue) -> Unit, () -> Unit) -> Unit = { text, change, confirm -> DefaultPreferenceField(text, change, confirm) },
) {
  var open by rememberSaveable { mutableStateOf(false) }
  Preference(title, modifier, enabled, icon, summary, onClick = { open = true })
  if (open) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
      val initial = valueToText(value)
      mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val confirm: () -> Unit = {
      val parsed = textToValue(text.text)
      if (parsed != null) { onValueChange(parsed); open = false }
    }
    val focus = remember { FocusRequester() }
    AlertDialog(
      onDismissRequest = { open = false }, title = title,
      modifier = Modifier.settingsGlassSurface(cornerRadius = 28.dp, independent = true), containerColor = Color.Transparent,
      text = { Box(Modifier.fillMaxWidth().focusRequester(focus)) { textField(text, { text = it }, confirm) } },
      confirmButton = { TextButton(onClick = confirm) { Text(stringResource(android.R.string.ok)) } },
      dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
  }
}

@Composable
fun <T> TextFieldPreference(
  state: MutableState<T>, title: @Composable () -> Unit, textToValue: (String) -> T?,
  modifier: Modifier = Modifier, enabled: Boolean = true, icon: (@Composable () -> Unit)? = null,
  summary: (@Composable () -> Unit)? = null, valueToText: (T) -> String = { it.toString() },
  textField: @Composable (TextFieldValue, (TextFieldValue) -> Unit, () -> Unit) -> Unit = { text, change, confirm -> DefaultPreferenceField(text, change, confirm) },
) = TextFieldPreference(state.value, { state.value = it }, title, textToValue, modifier, enabled, icon, summary, valueToText, textField)
