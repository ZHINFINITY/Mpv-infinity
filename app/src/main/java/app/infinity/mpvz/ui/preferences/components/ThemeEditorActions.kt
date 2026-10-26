/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.preferences.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import app.infinity.mpvz.ui.icons.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.icons.Icons

internal const val ThemeEditorResetTag = "theme_editor_reset"
internal const val ThemeEditorCancelTag = "theme_editor_cancel"
internal const val ThemeEditorSaveTag = "theme_editor_save"
internal const val ThemeEditorResetIconTag = "theme_editor_reset_icon"

/** Tighter than Material's 24dp sides so three buttons fit one row on narrow phones. */
private val ActionContentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/**
 * Reset, Cancel and Save in one row: equal widths (weight 1f each), equal heights, Save as
 * the primary button. Styling comes from the shared theme glass buttons, so light/dark and
 * the Liquid Glass switch behave like every other settings sheet action.
 */
@Composable
internal fun ThemeEditorActionRow(
  saveEnabled: Boolean,
  onReset: () -> Unit,
  onCancel: () -> Unit,
  onSave: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier.fillMaxWidth()) {
    // Labels re-fit from full size whenever the row width changes (rotation, sheet resize).
    val rowWidth = maxWidth
    Row(
      modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
      horizontalArrangement = Arrangement.spacedBy(ThemeEditorLayoutPolicy.ActionGapDp.dp),
    ) {
      val cell = Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp)
      ThemeGlassOutlinedButton(
        onClick = onReset,
        modifier = cell.testTag(ThemeEditorResetTag),
        contentPadding = ActionContentPadding,
      ) {
        val resetLabel = stringResource(R.string.generic_reset)
        // The icon is decorative: on very narrow screens it gives way before the label is cut.
        var showIcon by remember(resetLabel, rowWidth) { mutableStateOf(true) }
        if (showIcon) {
          Icon(
            Icons.RoundedFilled.Refresh,
            contentDescription = null,
            modifier = Modifier.size(18.dp).testTag(ThemeEditorResetIconTag),
          )
          Spacer(Modifier.width(4.dp))
        }
        FitButtonLabel(
          text = resetLabel,
          modifier = Modifier.weight(1f, fill = false),
          fitKey = rowWidth to showIcon,
          onDoesNotFit = if (showIcon) ({ showIcon = false }) else null,
        )
      }
      ThemeGlassOutlinedButton(
        onClick = onCancel,
        modifier = cell.testTag(ThemeEditorCancelTag),
        contentPadding = ActionContentPadding,
      ) {
        FitButtonLabel(stringResource(R.string.generic_cancel), fitKey = rowWidth)
      }
      ThemeGlassButton(
        onClick = onSave,
        enabled = saveEnabled,
        modifier = cell.testTag(ThemeEditorSaveTag),
        contentPadding = ActionContentPadding,
      ) {
        FitButtonLabel(stringResource(R.string.generic_save), fitKey = rowWidth)
      }
    }
  }
}

/**
 * Single-line label that steps its font down until it fits, then ellipsizes only if it
 * still overflows at [ThemeEditorLayoutPolicy.MinLabelSp] after [onDoesNotFit] freed space.
 * Drawing waits for the final size so a long translation never flashes clipped.
 */
@Composable
private fun FitButtonLabel(
  text: String,
  modifier: Modifier = Modifier,
  fitKey: Any? = null,
  onDoesNotFit: (() -> Unit)? = null,
) {
  val base = LocalTextStyle.current
  val startSp = if (base.fontSize.isSp) base.fontSize.value else 14f
  var fontSp by remember(text, startSp, fitKey) { mutableFloatStateOf(startSp) }
  var settled by remember(text, startSp, fitKey) { mutableStateOf(false) }
  Text(
    text = text,
    modifier = modifier.drawWithContent { if (settled) drawContent() },
    style = base.copy(fontSize = fontSp.sp),
    maxLines = 1,
    softWrap = false,
    overflow = TextOverflow.Ellipsis,
    onTextLayout = { result ->
      val overflowing = result.didOverflowWidth || result.isLineEllipsized(0)
      val next = ThemeEditorLayoutPolicy.nextLabelSp(fontSp, overflowing)
      when {
        next != null -> fontSp = next
        overflowing && onDoesNotFit != null -> onDoesNotFit()
        else -> settled = true
      }
    },
  )
}
