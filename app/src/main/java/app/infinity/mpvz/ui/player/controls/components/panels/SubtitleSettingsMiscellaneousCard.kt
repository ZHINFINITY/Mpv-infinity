/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.panels

import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.SubtitleLane

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.PlayerPreferences
import app.infinity.mpvz.preferences.SubtitlesPreferences
import app.infinity.mpvz.preferences.preference.deleteAndGet
import app.infinity.mpvz.presentation.components.ExpandableCard
import app.infinity.mpvz.presentation.components.SliderItem
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.applySubtitleLayout
import app.infinity.mpvz.ui.player.calculateSecondarySubtitlePosition
import app.infinity.mpvz.ui.player.controls.CARDS_MAX_WIDTH
import app.infinity.mpvz.ui.player.controls.components.sheets.toFixed
import app.infinity.mpvz.ui.player.controls.panelCardsColors
import app.infinity.mpvz.ui.player.resolveSecondarySubtitlePosition
import app.infinity.mpvz.ui.preferences.components.SwitchPreference
import app.infinity.mpvz.ui.theme.spacing
import app.infinity.mpvz.ui.utils.currentMpvConfigOverrideOptions
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.koin.compose.koinInject

@Composable
fun SubtitlesMiscellaneousCard(modifier: Modifier = Modifier) {
  val preferences = koinInject<SubtitlesPreferences>()
  val playerPreferences = koinInject<PlayerPreferences>()
  val configOwnedOptions = currentMpvConfigOverrideOptions()
  val layoutOptions = setOf("sub-ass-override", "secondary-sub-ass-override", "sub-pos", "secondary-sub-pos")
  val scaleOptions = setOf("sub-scale", "secondary-sub-scale")
  val scaleByWindowOptions = setOf("sub-scale-by-window", "sub-use-margins")
  val blendOptions = setOf("blend-subtitles")
  val miscellaneousOptions = layoutOptions + scaleOptions + scaleByWindowOptions + blendOptions
  var isExpanded by remember { mutableStateOf(true) }
  ExpandableCard(
    isExpanded,
    title = {
      Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium)) {
        Icon(Icons.RoundedFilled.Tune, null)
        Text(stringResource(R.string.player_sheets_sub_misc_card_title))
      }
    },
    onExpand = { isExpanded = !isExpanded },
    modifier.widthIn(max = CARDS_MAX_WIDTH),
    colors = panelCardsColors(),
  ) {
    ProvidePreferenceLocals {
      Column {
        var overrideAssSubs by remember {
          mutableStateOf(preferences.overrideAssSubs.get())
        }
        LaunchedEffect(preferences.overrideAssSubs.get()) {
          overrideAssSubs = preferences.overrideAssSubs.get()
        }
        SwitchPreference(
          overrideAssSubs,
          enabled = layoutOptions.none(configOwnedOptions::contains),
          onValueChange = {
            overrideAssSubs = it
            preferences.overrideAssSubs.set(it)
            applySubtitleLayout(PlaybackSession.getPropertyInt("sub-pos") ?: preferences.subPos.get(), it)
          },
          title = { Text(stringResource(R.string.player_sheets_sub_override_ass)) },
        )
        var scaleByWindow by remember {
          mutableStateOf(PlaybackSession.getPropertyString("sub-scale-by-window") == "yes")
        }
        SwitchPreference(
          scaleByWindow,
          enabled = scaleByWindowOptions.none(configOwnedOptions::contains),
          onValueChange = {
            scaleByWindow = it
            preferences.scaleByWindow.set(it)
            val value = if (it) "yes" else "no"
            PlaybackSession.setPropertyString("sub-scale-by-window", value)
            PlaybackSession.setPropertyString("sub-use-margins", value)
          },
          title = { Text(stringResource(R.string.player_sheets_sub_scale_by_window)) },
          summary = { Text(stringResource(R.string.player_sheets_sub_scale_by_window_summary)) },
        )
        var blendSubtitlesWithVideo by remember {
          mutableStateOf(preferences.blendSubtitlesWithVideo.get())
        }
        SwitchPreference(
          blendSubtitlesWithVideo,
          enabled = blendOptions.none(configOwnedOptions::contains),
          onValueChange = {
            blendSubtitlesWithVideo = it
            preferences.blendSubtitlesWithVideo.set(it)
            val blendMode = if (it && playerPreferences.isAmbientEnabled.get()) "video" else "no"
            PlaybackSession.setPropertyString("blend-subtitles", blendMode)
          },
          title = { Text(stringResource(R.string.player_sheets_sub_blend_with_video)) },
          summary = { Text(stringResource(R.string.player_sheets_sub_blend_with_video_summary)) },
        )
        var selectedLane by remember { mutableStateOf(SubtitleLane.Primary) }
        SubtitleLaneSelector(selected = selectedLane, onSelected = { selectedLane = it })
        val subScale by PlaybackSession.propFloat["sub-scale"].collectAsState()
        val secondarySubScale by PlaybackSession.propFloat["secondary-sub-scale"].collectAsState()
        val subPos by PlaybackSession.propInt["sub-pos"].collectAsState()
        val secondarySubPos by PlaybackSession.propInt["secondary-sub-pos"].collectAsState()
        val isPrimaryLane = selectedLane == SubtitleLane.Primary
        val scaleProperty = if (isPrimaryLane) "sub-scale" else "secondary-sub-scale"
        val positionProperty = if (isPrimaryLane) "sub-pos" else "secondary-sub-pos"
        val scaleFallback =
          if (isPrimaryLane) {
            preferences.subScale.get()
          } else {
            preferences.secondarySubScale.get().takeIf { it > 0f } ?: preferences.subScale.get()
          }
        val automaticSecondaryPosition = calculateSecondarySubtitlePosition(preferences.subPos.get())
        val positionFallback =
          if (isPrimaryLane) {
            preferences.subPos.get()
          } else {
            resolveSecondarySubtitlePosition(automaticSecondaryPosition, preferences.secondarySubPos.get())
          }
        val currentScale = if (isPrimaryLane) subScale else secondarySubScale
        val currentPosition = if (isPrimaryLane) subPos else secondarySubPos
        SliderItem(
          label = stringResource(R.string.player_sheets_sub_scale),
          value = currentScale ?: scaleFallback,
          valueText = (currentScale ?: scaleFallback).toFixed(2).toString(),
          onChange = {
            if (isPrimaryLane) preferences.subScale.set(it) else preferences.secondarySubScale.set(it)
            PlaybackSession.setPropertyFloat(scaleProperty, it)
          },
          max = 5f,
          enabled = scaleProperty !in configOwnedOptions,
          icon = {
            Icon(
              Icons.RoundedFilled.FormatSize,
              null,
            )
          },
        )
        SliderItem(
          label = stringResource(R.string.player_sheets_sub_position),
          value = currentPosition ?: positionFallback,
          valueText = (currentPosition ?: positionFallback).toString(),
          onChange = {
            if (isPrimaryLane) {
              preferences.subPos.set(it)
              applySubtitleLayout(it, preferences.overrideAssSubs.get())
            } else {
              preferences.secondarySubPos.set(it)
              PlaybackSession.setPropertyInt("secondary-sub-pos", it)
            }
          },
          max = 150,
          enabled = positionProperty !in configOwnedOptions,
          icon = {
            Icon(
              Icons.RoundedFilled.AlignVerticalCenter,
              null,
            )
          },
        )
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(end = MaterialTheme.spacing.medium, bottom = MaterialTheme.spacing.medium),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(
            enabled = miscellaneousOptions.none(configOwnedOptions::contains),
            onClick = {
              val defaultSubPos = preferences.subPos.deleteAndGet()
              val defaultSubScale = preferences.subScale.deleteAndGet()
              preferences.secondarySubPos.deleteAndGet()
              preferences.secondarySubScale.deleteAndGet()
              PlaybackSession.setPropertyFloat("sub-scale", defaultSubScale)
              PlaybackSession.setPropertyFloat("secondary-sub-scale", defaultSubScale)
              val defaultOverride = preferences.overrideAssSubs.deleteAndGet()
              overrideAssSubs = defaultOverride
              applySubtitleLayout(defaultSubPos, defaultOverride)
              val defaultScaleByWindow = preferences.scaleByWindow.deleteAndGet()
              scaleByWindow = defaultScaleByWindow
              val scaleValue = if (defaultScaleByWindow) "yes" else "no"
              PlaybackSession.setPropertyString("sub-scale-by-window", scaleValue)
              PlaybackSession.setPropertyString("sub-use-margins", scaleValue)
              val defaultBlendSubtitles = preferences.blendSubtitlesWithVideo.deleteAndGet()
              blendSubtitlesWithVideo = defaultBlendSubtitles
              val blendMode = if (defaultBlendSubtitles && playerPreferences.isAmbientEnabled.get()) "video" else "no"
              PlaybackSession.setPropertyString("blend-subtitles", blendMode)
            },
          ) {
            Row {
              Icon(Icons.RoundedFilled.EditOff, null)
              Text(stringResource(R.string.generic_reset))
            }
          }
        }
      }
    }
  }
}
