/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.preferences

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.BuildConfig
import app.infinity.mpvz.R
import app.infinity.mpvz.domain.anime4k.Anime4KManager
import app.infinity.mpvz.preferences.AdvancedPreferences
import app.infinity.mpvz.preferences.DecoderPreferences
import app.infinity.mpvz.preferences.MpvConfigControlledFeatures
import app.infinity.mpvz.preferences.MpvConfigOverride
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.ui.player.AnvilMotionVectorExportCapability
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.AnvilFrameInterpolationTelemetry
import app.infinity.mpvz.ui.player.Debanding
import app.infinity.mpvz.ui.player.MPVProfile
import app.infinity.mpvz.ui.player.PlaybackEngineMode
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.anvilMotionVectorExportCapability
import app.infinity.mpvz.ui.preferences.components.SwitchPreference
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.LocalShowSettingsBackArrow
import app.infinity.mpvz.ui.utils.popSafely
import app.infinity.mpvz.utils.device.VulkanCapabilities
import kotlinx.serialization.Serializable
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.koin.compose.koinInject

@Serializable
object DecoderPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val preferences = koinInject<DecoderPreferences>()
    val advancedPreferences = koinInject<AdvancedPreferences>()
    val selectedPlaybackEngine by preferences.playbackEngine.collectAsState()
    val anvilFrameInterpolationEnabled by preferences.anvilFrameInterpolation.collectAsState()
    val rifeFrameInterpolationEnabled by preferences.rifeFrameInterpolation.collectAsState()
    val anvilTelemetry by PlaybackSession.anvilFrameInterpolationTelemetry.collectAsState()
    val sourceVideoCodec by PlaybackSession.propString["video-codec"].collectAsState()
    val activeHardwareDecoder by PlaybackSession.propString["hwdec-current"].collectAsState()
    val storedConfigOverrides by advancedPreferences.mpvConfOverrides.collectAsState()
    val configOwnedOptions =
      remember(storedConfigOverrides) { MpvConfigOverride.resolveOptionNames(storedConfigOverrides) }
    val profileConfigOwned = "profile" in configOwnedOptions
    val rendererBackendConfigOwned = setOf("gpu-api", "gpu-context").any(configOwnedOptions::contains)
    val decoderConfigOwned = MpvConfigControlledFeatures.HARDWARE_DECODER.any(configOwnedOptions::contains)
    val frameInterpolationConfigOwned =
      MpvConfigControlledFeatures.FRAME_INTERPOLATION.any(configOwnedOptions::contains)
    val shadersConfigOwned = MpvConfigControlledFeatures.ANIME4K.any(configOwnedOptions::contains)
    val debandingConfigOwned =
      setOf("vf", "deband", "deband-iterations", "deband-threshold", "deband-range", "deband-grain")
        .any(configOwnedOptions::contains)
    val yuv420ConfigOwned = "vf" in configOwnedOptions
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val isDeviceVulkanSupported = remember { VulkanCapabilities.isDeviceSupported(context) }
    val isVulkanSupported = BuildConfig.MPV_SUPPORTS_VULKAN && isDeviceVulkanSupported
    var showGpuNextWarning by remember { mutableStateOf(false) }
    var anime4kExpanded by remember { mutableStateOf(false) }
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_decoder),
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.ExtraBold,
              color = MaterialTheme.colorScheme.primary,
            )
          },
          navigationIcon = {
            if (LocalShowSettingsBackArrow.current) {
              IconButton(onClick = { backstack.popSafely() }) {
                Icon(
                  Icons.RoundedFilled.ArrowBack,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
            }
          },
        )
      },
    ) { padding ->
      ProvidePreferenceLocals {
        val (settingsListState, settingsHighlight) =
          rememberSettingsSearchList(DecoderPreferencesScreen, MaterialTheme.colorScheme.primary)
        LazyColumn(
          state = settingsListState,
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding)
              .then(settingsHighlight),
        ) {
          item {
            PreferenceSectionHeader(
              title = stringResource(R.string.pref_decoder),
              modifier = Modifier.settingsSearchTarget(R.string.pref_decoder),
            )
          }

          item {
            PreferenceCard {
              val playbackEngine by preferences.playbackEngine.collectAsState()
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_engine_title),
                value = playbackEngine,
                onValueChange = { preferences.playbackEngine.set(it) },
                values = PlaybackEngineMode.entries,
                title = { Text(stringResource(R.string.pref_decoder_engine_title)) },
                summary = { Text(playbackEngine.title, color = MaterialTheme.colorScheme.outline) },
              )

              PreferenceDivider()

              val profile by preferences.profile.collectAsState()
              val currentProfile = MPVProfile.fromValue(profile)
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_profile_title),
                value = currentProfile,
                onValueChange = { preferences.profile.set(it.value) },
                values = MPVProfile.entries,
                enabled = !profileConfigOwned,
                title = { Text(stringResource(R.string.pref_decoder_profile_title)) },
                summary = {
                  Text(
                    currentProfile.displayName,
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val tryHWDecoding by preferences.tryHWDecoding.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_try_hw_dec_title),
                value = tryHWDecoding,
                enabled = !decoderConfigOwned,
                onValueChange = {
                  preferences.tryHWDecoding.set(it)
                },
                title = { Text(stringResource(R.string.pref_decoder_try_hw_dec_title)) },
              )

              PreferenceDivider()

              val gpuNext by preferences.gpuNext.collectAsState()
              val useVulkan by preferences.useVulkan.collectAsState() // Added to check Vulkan state
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_gpu_next_title),
                value = gpuNext,
                onValueChange = { enabled ->
                  if (enabled && !gpuNext && !useVulkan) { // Only show warning if Vulkan is disabled
                    showGpuNextWarning = true
                  } else {
                    preferences.gpuNext.set(enabled)
                    if (enabled && !useVulkan) { // Only disable Anime4K if Vulkan is disabled
                      preferences.enableAnime4K.set(false)
                    }
                  }
                },
                title = { Text(stringResource(R.string.pref_decoder_gpu_next_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_decoder_gpu_next_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              if (showGpuNextWarning) {
                AlertDialog(
                  onDismissRequest = { showGpuNextWarning = false },
                  title = { Text(stringResource(R.string.pref_decoder_gpu_next_enable_title)) },
                  text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                      Text(stringResource(R.string.pref_decoder_gpu_next_warning))
                      Text(stringResource(R.string.pref_decoder_gpu_next_purple_screen_fix))

                      Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                      ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                          Text(
                            text = stringResource(R.string.pref_anime4k_incompatibility),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                          )
                          Text(
                            text = stringResource(R.string.pref_anime4k_gpu_next_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                          )
                        }
                      }
                    }
                  },
                  confirmButton = {
                    Button(onClick = {
                      preferences.gpuNext.set(true)
                      preferences.enableAnime4K.set(false) // Ensure Anime4K is disabled on confirmation
                      showGpuNextWarning = false
                    }) {
                      Text(stringResource(R.string.pref_decoder_gpu_next_enable_anyway))
                    }
                  },
                  dismissButton = {
                    TextButton(onClick = { showGpuNextWarning = false }) {
                      Text(stringResource(R.string.generic_cancel))
                    }
                  },
                )
              }

              PreferenceDivider()

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_vulkan_experimental_title),
                value = useVulkan && isVulkanSupported,
                onValueChange = { enabled ->
                  if (isVulkanSupported) {
                    preferences.useVulkan.set(enabled)
                    // When Vulkan is disabled, ensure Anime4K and GPU Next are not both enabled
                    if (!enabled) {
                      val anime4kEnabled = preferences.enableAnime4K.get()
                      val gpuNextEnabled = preferences.gpuNext.get()
                      if (anime4kEnabled && gpuNextEnabled) {
                        // Disable GPU Next to keep Anime4K
                        preferences.gpuNext.set(false)
                      }
                    }
                  }
                },
                enabled = isVulkanSupported && !rendererBackendConfigOwned,
                title = { Text(stringResource(R.string.pref_decoder_vulkan_experimental_title)) },
                summary = {
                  Text(
                    stringResource(
                      when {
                        !BuildConfig.MPV_SUPPORTS_VULKAN && isDeviceVulkanSupported ->
                          R.string.pref_decoder_vulkan_excluded_supported_device
                        !BuildConfig.MPV_SUPPORTS_VULKAN ->
                          R.string.pref_decoder_vulkan_excluded_unsupported_device
                        isDeviceVulkanSupported -> R.string.pref_decoder_vulkan_summary
                        else -> R.string.pref_decoder_vulkan_not_supported
                      },
                    ),
                    color =
                      if (isVulkanSupported) {
                        MaterialTheme.colorScheme.outline
                      } else {
                        MaterialTheme.colorScheme.error
                      },
                  )
                },
              )

              PreferenceDivider()

              val debanding by preferences.debanding.collectAsState()
              ListPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_debanding_title),
                value = debanding,
                onValueChange = { preferences.debanding.set(it) },
                values = Debanding.entries,
                enabled = !debandingConfigOwned,
                title = { Text(stringResource(R.string.pref_decoder_debanding_title)) },
                summary = {
                  Text(
                    debanding.name,
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val useYUV420p by preferences.useYUV420P.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_yuv420p_title),
                value = useYUV420p,
                enabled = !yuv420ConfigOwned,
                onValueChange = {
                  preferences.useYUV420P.set(it)
                },
                title = { Text(stringResource(R.string.pref_decoder_yuv420p_title)) },
                summary = {
                  Text(
                    stringResource(R.string.pref_decoder_yuv420p_summary),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_anvil_title),
                value = anvilFrameInterpolationEnabled,
                enabled =
                  BuildConfig.MPV_HAS_ANVIL &&
                    !frameInterpolationConfigOwned &&
                    selectedPlaybackEngine != PlaybackEngineMode.NATIVE,
                onValueChange = {
                  preferences.anvilFrameInterpolation.set(it)
                  if (it) preferences.rifeFrameInterpolation.set(false)
                },
                title = { Text(stringResource(R.string.pref_decoder_anvil_title)) },
                summary = {
                  Text(
                    stringResource(
                      when {
                        !BuildConfig.MPV_HAS_ANVIL -> R.string.pref_decoder_anvil_summary_unavailable
                        frameInterpolationConfigOwned -> R.string.pref_decoder_anvil_summary_config_owned
                        selectedPlaybackEngine == PlaybackEngineMode.NATIVE ->
                          R.string.pref_decoder_anvil_summary_native_engine
                        else -> R.string.pref_decoder_anvil_summary
                      },
                    ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              AnvilFrameInterpolationStatusCard(
                telemetry = anvilTelemetry,
                preferenceEnabled = anvilFrameInterpolationEnabled,
                nativeFilterIncluded = BuildConfig.MPV_HAS_ANVIL,
                configOwned = frameInterpolationConfigOwned,
                playbackEngine = selectedPlaybackEngine,
                sourceCodec = sourceVideoCodec,
                activeHardwareDecoder = activeHardwareDecoder,
              )

              PreferenceDivider()

              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_decoder_rife_title),
                value = rifeFrameInterpolationEnabled,
                enabled =
                  BuildConfig.MPV_HAS_RIFE &&
                    isVulkanSupported &&
                    !frameInterpolationConfigOwned &&
                    selectedPlaybackEngine != PlaybackEngineMode.NATIVE,
                onValueChange = {
                  preferences.rifeFrameInterpolation.set(it)
                  if (it) preferences.anvilFrameInterpolation.set(false)
                },
                title = { Text(stringResource(R.string.pref_decoder_rife_title)) },
                summary = {
                  Text(
                    stringResource(
                      when {
                        !BuildConfig.MPV_HAS_RIFE -> R.string.pref_decoder_rife_summary_unavailable
                        frameInterpolationConfigOwned -> R.string.pref_decoder_rife_summary_config_owned
                        selectedPlaybackEngine == PlaybackEngineMode.NATIVE ->
                          R.string.pref_decoder_rife_summary_native_engine
                        !isVulkanSupported -> R.string.pref_decoder_rife_summary_vulkan_unavailable
                        else -> R.string.pref_decoder_rife_summary
                      },
                    ),
                    color = MaterialTheme.colorScheme.outline,
                  )
                },
              )

              PreferenceDivider()

              val enableAnime4K by preferences.enableAnime4K.collectAsState()
              SwitchPreference(
                modifier = Modifier.settingsSearchTarget(R.string.pref_anime4k_title),
                value = enableAnime4K,
                enabled = !shadersConfigOwned,
                onValueChange = { enabled ->
                  preferences.enableAnime4K.set(enabled)
                  if (enabled && !useVulkan) {
                    preferences.gpuNext.set(false)
                  }
                  if (enabled) {
                    anime4kExpanded = true
                  }
                },
                title = { Text(stringResource(R.string.pref_anime4k_title)) },
                summary = {
                  Column {
                    Text(
                      stringResource(R.string.pref_anime4k_summary),
                      color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                      text =
                        androidx.compose.ui.res
                          .stringResource(app.infinity.mpvz.R.string.pref_anime4k_link),
                      color = MaterialTheme.colorScheme.primary,
                      style = MaterialTheme.typography.bodySmall,
                      textDecoration = TextDecoration.Underline,
                      modifier =
                        Modifier.clickable {
                          val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bloc97/Anime4K"))
                          context.startActivity(intent)
                        },
                    )
                  }
                },
              )

              if (enableAnime4K && !shadersConfigOwned) {
                val rotationState by animateFloatAsState(
                  targetValue = if (anime4kExpanded) 180f else 0f,
                  label = "anime4k_chevron_rotation",
                )

                PreferenceDivider()

                Surface(
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 8.dp, vertical = 4.dp),
                  shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                  Column {
                    Row(
                      modifier =
                        Modifier
                          .fillMaxWidth()
                          .clickable { anime4kExpanded = !anime4kExpanded }
                          .padding(vertical = 12.dp, horizontal = 16.dp),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically,
                    ) {
                      Text(
                        text =
                          androidx.compose.ui.res.stringResource(
                            app.infinity.mpvz.R.string.ui_anime4k_shaders_options,
                          ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                      )
                      Icon(
                        Icons.RoundedFilled.KeyboardArrowDown,
                        contentDescription = if (anime4kExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.rotate(rotationState),
                      )
                    }

                    AnimatedVisibility(visible = anime4kExpanded) {
                      Column {
                        val anime4kIn4k by preferences.anime4kIn4k.collectAsState()
                        SwitchPreference(
                          value = anime4kIn4k,
                          onValueChange = { preferences.anime4kIn4k.set(it) },
                          title = { Text(stringResource(R.string.pref_anime4k_in_4k_title)) },
                          summary = {
                            Text(
                              stringResource(R.string.pref_anime4k_in_4k_summary),
                              color = MaterialTheme.colorScheme.outline,
                            )
                          },
                        )

                        PreferenceDivider()

                        val anime4kQuality by preferences.anime4kQuality.collectAsState()
                        ListPreference(
                          value = anime4kQuality,
                          onValueChange = { preferences.anime4kQuality.set(it) },
                          values = Anime4KManager.Quality.entries,
                          valueToText = { AnnotatedString(context.getString(it.titleRes)) },
                          title = { Text(stringResource(R.string.pref_anime4k_quality_title)) },
                          summary = {
                            Text(
                              stringResource(anime4kQuality.titleRes),
                              color = MaterialTheme.colorScheme.outline,
                            )
                          },
                        )

                        PreferenceDivider()

                        val anime4kDarken by preferences.anime4kDarken.collectAsState()
                        SwitchPreference(
                          value = anime4kDarken,
                          onValueChange = { preferences.anime4kDarken.set(it) },
                          title = { Text(stringResource(R.string.pref_anime4k_darken_title)) },
                          summary = {
                            Text(
                              stringResource(R.string.pref_anime4k_darken_summary),
                              color = MaterialTheme.colorScheme.outline,
                            )
                          },
                        )

                        PreferenceDivider()

                        val anime4kThin by preferences.anime4kThin.collectAsState()
                        SwitchPreference(
                          value = anime4kThin,
                          onValueChange = { preferences.anime4kThin.set(it) },
                          title = { Text(stringResource(R.string.pref_anime4k_thin_title)) },
                          summary = {
                            Text(
                              stringResource(R.string.pref_anime4k_thin_summary),
                              color = MaterialTheme.colorScheme.outline,
                            )
                          },
                        )

                        PreferenceDivider()

                        val anime4kDeblur by preferences.anime4kDeblur.collectAsState()
                        SwitchPreference(
                          value = anime4kDeblur,
                          onValueChange = { preferences.anime4kDeblur.set(it) },
                          title = { Text(stringResource(R.string.pref_anime4k_deblur_title)) },
                          summary = {
                            Text(
                              stringResource(R.string.pref_anime4k_deblur_summary),
                              color = MaterialTheme.colorScheme.outline,
                            )
                          },
                        )
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AnvilFrameInterpolationStatusCard(
  telemetry: AnvilFrameInterpolationTelemetry,
  preferenceEnabled: Boolean,
  nativeFilterIncluded: Boolean,
  configOwned: Boolean,
  playbackEngine: PlaybackEngineMode,
  sourceCodec: String?,
  activeHardwareDecoder: String?,
) {
  val codecCapability = anvilMotionVectorExportCapability(sourceCodec)
  val codecLabel = sourceCodec?.takeIf { it.isNotBlank() } ?: stringResource(R.string.pref_decoder_anvil_codec_unknown)
  val decoderLabel =
    when {
      activeHardwareDecoder.isNullOrBlank() -> stringResource(R.string.pref_decoder_anvil_decoder_unknown)
      activeHardwareDecoder.equals("no", ignoreCase = true) -> stringResource(R.string.pref_decoder_anvil_decoder_software)
      else -> stringResource(R.string.pref_decoder_anvil_decoder_hardware, activeHardwareDecoder)
    }
  val exportOptionLabel =
    when (telemetry.motionVectorExportOptionAccepted) {
      true -> stringResource(R.string.pref_decoder_anvil_option_accepted)
      false -> stringResource(R.string.pref_decoder_anvil_option_failed)
      null -> stringResource(R.string.pref_decoder_anvil_option_not_requested)
    }
  val softwareOptionLabel =
    when (telemetry.softwareDecodeOptionAccepted) {
      true -> stringResource(R.string.pref_decoder_anvil_option_accepted)
      false -> stringResource(R.string.pref_decoder_anvil_option_failed)
      null -> stringResource(R.string.pref_decoder_anvil_option_not_requested)
    }
  val hardwareDecoderActive =
    activeHardwareDecoder?.let { it.isNotBlank() && !it.equals("no", ignoreCase = true) } == true
  val statusRes =
    when {
      !nativeFilterIncluded -> R.string.pref_decoder_anvil_status_missing_native
      configOwned -> R.string.pref_decoder_anvil_status_config_owned
      playbackEngine == PlaybackEngineMode.NATIVE -> R.string.pref_decoder_anvil_status_native_engine
      !preferenceEnabled -> R.string.pref_decoder_anvil_status_off
      telemetry.generatedFrames > 0L && telemetry.activeForCore ->
        R.string.pref_decoder_anvil_status_generating
      telemetry.generatedFrames > 0L -> R.string.pref_decoder_anvil_status_last_run
      codecCapability == AnvilMotionVectorExportCapability.UNSUPPORTED ->
        R.string.pref_decoder_anvil_status_codec_unsupported
      telemetry.motionVectorExportOptionAccepted == false || telemetry.softwareDecodeOptionAccepted == false ->
        R.string.pref_decoder_anvil_status_option_failed
      telemetry.inputFrames > 0L && hardwareDecoderActive ->
        R.string.pref_decoder_anvil_status_hardware_decoder
      telemetry.inputFrames > 0L && telemetry.lastMotionVectors == 0 &&
        telemetry.intervalVectorFrames == 0 &&
        codecCapability == AnvilMotionVectorExportCapability.SUPPORTED ->
        R.string.pref_decoder_anvil_status_supported_no_vectors
      telemetry.inputFrames > 0L && telemetry.lastMotionVectors == 0 &&
        telemetry.intervalVectorFrames == 0 ->
        R.string.pref_decoder_anvil_status_no_motion_vectors
      telemetry.inputFrames > 0L &&
        (telemetry.lastMotionVectors > 0 || telemetry.intervalVectorFrames > 0) &&
        telemetry.generatedFrames == 0L ->
        R.string.pref_decoder_anvil_status_vectors_seen
      telemetry.filterSeen -> R.string.pref_decoder_anvil_status_filter_loaded
      else -> R.string.pref_decoder_anvil_status_waiting
    }

  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceVariant,
    tonalElevation = 1.dp,
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = stringResource(R.string.pref_decoder_anvil_status_title),
        style = MaterialTheme.typography.titleSmall,
      )
      Text(
        text = stringResource(statusRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = stringResource(R.string.pref_decoder_anvil_source_decoder, codecLabel, decoderLabel),
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        text = stringResource(R.string.pref_decoder_anvil_option_results, exportOptionLabel, softwareOptionLabel),
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        text =
          stringResource(
            R.string.pref_decoder_anvil_generated_frames,
            telemetry.generatedFrames,
          ),
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        text = stringResource(
          R.string.pref_decoder_anvil_input_stats,
          telemetry.inputFrames,
          telemetry.lastMotionVectors,
          stringResource(
            when (telemetry.motionVectorSideData) {
              "missing" -> R.string.pref_decoder_anvil_side_data_missing
              "empty" -> R.string.pref_decoder_anvil_side_data_empty
              "present" -> R.string.pref_decoder_anvil_side_data_present
              else -> R.string.pref_decoder_anvil_side_data_unknown
            },
            ),
          telemetry.intervalMaxMotionVectors,
          telemetry.intervalVectorFrames,
        ),
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        text =
          stringResource(
            R.string.pref_decoder_anvil_backend_stats,
            telemetry.backend,
            telemetry.qnn,
          ),
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}
