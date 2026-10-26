/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.update

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.infinity.mpvz.BuildConfig
import app.infinity.mpvz.domain.update.AppUpdateChannel
import app.infinity.mpvz.domain.update.Release
import app.infinity.mpvz.domain.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UpdateViewModel private constructor(
  application: Application,
  private val startupUpdateCheckDelayMs: Long,
  private val updateCheckOverride: (suspend (AppUpdateChannel, Boolean) -> Release?)?,
) : AndroidViewModel(application) {
  constructor(application: Application) : this(application, UPDATE_CHECK_STARTUP_DELAY_MS, null)

  internal constructor(
    application: Application,
    updateCheckOverride: suspend (AppUpdateChannel, Boolean) -> Release?,
  ) : this(application, UPDATE_CHECK_STARTUP_DELAY_MS, updateCheckOverride)

  private val updateManager = UpdateManager(application)

  private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
  val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()
  private val _isManualUpdateCheck = MutableStateFlow(false)
  val isManualUpdateCheck: StateFlow<Boolean> = _isManualUpdateCheck.asStateFlow()

  private val _downloadProgress = MutableStateFlow<Float>(0f)
  val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

  private val _isDownloading = MutableStateFlow(false)
  val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()
  private var updateCheckJob: Job? = null
  private var startupUpdateCheckJob: Job? = null
  private var updateCheckRequestGeneration = 0L

  private val prefs = application.getSharedPreferences("Mpv∞_prefs", Context.MODE_PRIVATE)
  private val _isAutoUpdateEnabled =
    MutableStateFlow(
      if (BuildConfig.ENABLE_UPDATE_FEATURE) prefs.getBoolean("auto_update", false) else false,
    )
  val isAutoUpdateEnabled: StateFlow<Boolean> = _isAutoUpdateEnabled.asStateFlow()

  private val _updateChannel =
    MutableStateFlow(
      prefs.getString(UPDATE_CHANNEL_KEY, null)?.let(AppUpdateChannel::fromStoredValue)
        ?: if (BuildConfig.IS_PREVIEW_BUILD) AppUpdateChannel.PREVIEW else AppUpdateChannel.STABLE,
    )
  val updateChannel: StateFlow<AppUpdateChannel> = _updateChannel.asStateFlow()

  fun setUpdateChannel(channel: AppUpdateChannel) {
    if (!BuildConfig.ENABLE_UPDATE_FEATURE || channel == _updateChannel.value) return

    prefs.edit().putString(UPDATE_CHANNEL_KEY, channel.name).apply()
    _updateChannel.value = channel
    updateManager.clearCache()
    _updateState.value = UpdateState.Idle
    checkForUpdate(manual = true)
  }

  fun toggleAutoUpdate(enabled: Boolean) {
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    prefs.edit().putBoolean("auto_update", enabled).apply()
    _isAutoUpdateEnabled.value = enabled
    if (enabled) {
      checkForUpdate(manual = false)
    } else {
      cancelStartupUpdateCheck()
    }
  }

  init {
    // Only initialize auto-update if feature is enabled.
    // The network check is deferred by a short delay so that it does not
    // race with first-frame composition of the main UI. Previously the
    // check fired synchronously inside the init block of the ViewModel,
    // which is itself constructed during the very first Compose
    // composition in MainActivity.Navigator() -- meaning the OkHttp
    // client + GitHub API call competed with cold-start rendering.
    // See issue 1.5 in the startup audit.
    if (BuildConfig.ENABLE_UPDATE_FEATURE && isAutoUpdateEnabled.value) {
      val scheduledGeneration = updateCheckRequestGeneration
      startupUpdateCheckJob = viewModelScope.launch {
        delay(startupUpdateCheckDelayMs)
        if (!StartupUpdateCheckPolicy.shouldRunDelayedStartupCheck(
            scheduledGeneration = scheduledGeneration,
            currentGeneration = updateCheckRequestGeneration,
            manualCheckInProgress = _isManualUpdateCheck.value,
          )
        ) {
          return@launch
        }
        startupUpdateCheckJob = null
        startUpdateCheck(manual = false)
      }
    }
  }

  private companion object {
    /**
     * Delay before the auto-update network check fires after the
     * UpdateViewModel is constructed. Long enough to let the first frame
     * draw and the main navigation settle, short enough that the update
     * dialog (if any) still appears within a few seconds of cold start.
     */
    private const val UPDATE_CHECK_STARTUP_DELAY_MS = 1500L
    private const val UPDATE_CHANNEL_KEY = "app_update_channel"
  }

  sealed class UpdateState {
    object Idle : UpdateState()

    object Loading : UpdateState()

    data class Available(
      val release: Release,
    ) : UpdateState()

    object NoUpdate : UpdateState()

    object Error : UpdateState()

    data class ReadyToInstall(
      val release: Release,
    ) : UpdateState()
  }

  fun dismissNoUpdate() {
    updateCheckJob?.cancel()
    updateCheckJob = null
    _isManualUpdateCheck.value = false
    _updateState.value = UpdateState.Idle
  }

  fun checkForUpdate(manual: Boolean = false) {
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    if (!manual && !UpdateCheckRequestPolicy.shouldStartAutomaticCheck(
        manualCheckInProgress = _isManualUpdateCheck.value,
        manualRequestActive = updateCheckJob?.isActive == true,
      )
    ) {
      return
    }

    cancelStartupUpdateCheck()
    startUpdateCheck(manual)
  }

  private fun cancelStartupUpdateCheck() {
    updateCheckRequestGeneration += 1L
    startupUpdateCheckJob?.cancel()
    startupUpdateCheckJob = null
  }

  private fun startUpdateCheck(manual: Boolean) {
    _isManualUpdateCheck.value = manual
    updateCheckJob?.cancel()
    updateCheckJob = viewModelScope.launch {
      _updateState.value = UpdateState.Loading
      try {
        val release =
          if (updateCheckOverride == null) {
            updateManager.checkForUpdate(channel = _updateChannel.value, forceShow = manual)
          } else {
            updateCheckOverride.invoke(_updateChannel.value, manual)
          }
        if (release != null) {
          val existingFile = updateManager.getApkFile(release)
          if (existingFile != null) {
            _updateState.value = UpdateState.ReadyToInstall(release)
          } else {
            _updateState.value = UpdateState.Available(release)
          }
        } else {
          if (manual) {
            _updateState.value = UpdateState.NoUpdate
          } else {
            _updateState.value = UpdateState.Idle
          }
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (e: Exception) {
        e.printStackTrace()
        if (manual) {
          _updateState.value = UpdateState.Error
        } else {
          _updateState.value = UpdateState.Idle
        }
      }
    }
  }

  fun downloadUpdate(release: Release) {
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    viewModelScope.launch {
      _isDownloading.value = true
      try {
        updateManager.downloadUpdate(release).collect { progress ->
          _downloadProgress.value = progress
        }
        _isDownloading.value = false
        _updateState.value = UpdateState.ReadyToInstall(release)
      } catch (e: Exception) {
        e.printStackTrace()
        _isDownloading.value = false
        _updateState.value = UpdateState.Error
      }
    }
  }

  fun installUpdate(release: Release) {
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    val file = updateManager.getApkFile(release) ?: return
    val context = getApplication<Application>()
    val uri =
      FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file,
      )
    val intent =
      Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    context.startActivity(intent)
  }

  fun dismiss() {
    // Clean up downloaded APK when user dismisses the dialog
    updateManager.clearCache()
    _updateState.value = UpdateState.Idle
  }

  fun ignoreVersion(version: String) {
    updateManager.ignoreVersion(version, _updateChannel.value)
    _updateState.value = UpdateState.Idle
  }
}
