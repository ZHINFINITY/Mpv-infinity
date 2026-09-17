/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.domain.update

import android.content.Context
import android.os.Build
import app.infinity.mpvz.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class UpdateManager(
  private val context: Context,
) {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun checkForUpdate(
    channel: AppUpdateChannel,
    forceShow: Boolean = false,
  ): Release? {
    // Return null immediately if update checks are disabled for this build.
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return null
    }

    val release =
      getLatestRelease(
        when (channel) {
          AppUpdateChannel.STABLE -> STABLE_RELEASE_URL
          AppUpdateChannel.PREVIEW -> PREVIEW_RELEASE_URL
        },
      )
    if (selectBestApkAsset(release.assets) == null) {
      return null
    }
    val prefs = context.getSharedPreferences("Mpv∞_prefs", Context.MODE_PRIVATE)
    val ignoredVersion =
      prefs.getString(ignoredVersionKey(channel), null)
        ?: if (channel == AppUpdateChannel.STABLE) prefs.getString(LEGACY_IGNORED_VERSION_KEY, null) else null

    // If this version was ignored, don't show it unless forced (manual check)
    val isIgnored =
      ignoredVersion == release.tagName ||
        (channel == AppUpdateChannel.STABLE && ignoredVersion == release.tagName.removePrefix("v"))
    if (!forceShow && isIgnored) {
      return null
    }

    val isNewer =
      when (channel) {
        AppUpdateChannel.STABLE -> {
          val currentVersion = BuildConfig.VERSION_NAME.substringBefore('-')
          isNewerVersion(release.tagName.removePrefix("v"), currentVersion)
        }
        AppUpdateChannel.PREVIEW -> {
          val remoteCommitCount = release.commitCount ?: parsePreviewCommitCount(release.tagName)
          remoteCommitCount != null && remoteCommitCount > BuildConfig.GIT_COUNT
        }
      }

    return if (isNewer) {
      release
    } else {
      null
    }
  }

  fun ignoreVersion(
    version: String,
    channel: AppUpdateChannel,
  ) {
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    val prefs = context.getSharedPreferences("Mpv∞_prefs", Context.MODE_PRIVATE)
    prefs
      .edit()
      .putString(ignoredVersionKey(channel), version)
      .apply()
  }

  private suspend fun getLatestRelease(url: String): Release =
    withContext(Dispatchers.IO) {
      val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        val responseBody = response.body.string()
        json.decodeFromString<Release>(responseBody)
      }
    }

  private fun isNewerVersion(
    remote: String,
    current: String,
  ): Boolean {
    val rParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
    val cParts = current.split(".").map { it.toIntOrNull() ?: 0 }

    for (i in 0 until maxOf(rParts.size, cParts.size)) {
      val r = rParts.getOrElse(i) { 0 }
      val c = cParts.getOrElse(i) { 0 }
      if (r > c) return true
      if (r < c) return false
    }
    return false
  }

  private fun parsePreviewCommitCount(tagName: String): Int? =
    PREVIEW_TAG_REGEX.find(tagName)?.groupValues?.getOrNull(1)?.toIntOrNull()

  private fun ignoredVersionKey(channel: AppUpdateChannel): String =
    "ignored_version_${channel.name.lowercase()}"

  fun downloadUpdate(release: Release): Flow<Float> {
    // Return completed flow immediately if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return flowOf(100f)
    }

    val asset =
      selectBestApkAsset(release.assets)
        ?: throw Exception("No compatible APK asset found")

    val destination = File(context.externalCacheDir, asset.name)
    return downloadApk(asset.downloadUrl, destination)
  }

  private fun selectBestApkAsset(assets: List<Asset>): Asset? {
    val deviceArch = getDeviceArchitecture()
      val compatibleAssets =
      assets.filter { asset ->
        (asset.name.startsWith("Mpv", ignoreCase = true) ||
          asset.name.startsWith("mpv-infinity-", ignoreCase = true)) &&
          asset.name.endsWith(".apk", ignoreCase = true) &&
          asset.name.matchesApkVariant(BuildConfig.UPDATE_APK_VARIANT)
      }

    // First, try to find architecture-specific APK
    val archSpecificApk =
      compatibleAssets.firstOrNull { asset ->
        asset.name.hasAssetToken(deviceArch)
      }

    if (archSpecificApk != null) {
      return archSpecificApk
    }

    // Fallback to universal APK
    val universalApk =
      compatibleAssets.firstOrNull { asset ->
        asset.name.hasAssetToken("universal")
      }

    if (universalApk != null) {
      return universalApk
    }

    // FongMi and No-Vulkan universal assets use the flavor marker instead of "universal".
    return compatibleAssets.firstOrNull { asset ->
      SUPPORTED_ARCHITECTURES.none { architecture -> asset.name.hasAssetToken(architecture) }
    }
  }

  private fun String.matchesApkVariant(variant: String): Boolean {
    val isFongMi = hasAssetToken("fongmi")
    val isNoVulkan = hasAssetToken("no-vulkan")
    return when (variant) {
      "fongmi" -> isFongMi
      "no-vulkan" -> isNoVulkan
      "standard" -> !isFongMi && !isNoVulkan
      else -> false
    }
  }

  private fun String.hasAssetToken(token: String): Boolean =
    Regex(
      pattern = "(?:^|-)${Regex.escape(token)}(?:-|\\.apk$)",
      option = RegexOption.IGNORE_CASE,
    ).containsMatchIn(this)

  private fun getDeviceArchitecture(): String {
    // Get the primary ABI (Application Binary Interface)
    val primaryAbi =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        Build.SUPPORTED_ABIS[0]
      } else {
        @Suppress("DEPRECATION")
        Build.CPU_ABI
      }

    // Map Android ABI names to your APK naming convention
    return when (primaryAbi) {
      "arm64-v8a" -> "arm64-v8a"
      "armeabi-v7a" -> "armeabi-v7a"
      "x86" -> "x86"
      "x86_64" -> "x86_64"
      else -> "universal" // Fallback for unknown architectures
    }
  }

  private fun downloadApk(
    url: String,
    destination: File,
  ): Flow<Float> =
    flow {
      val request = Request.Builder().url(url).build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")

        response.body.use { body ->
          val contentLength = body.contentLength()
          val inputStream = body.byteStream()
          FileOutputStream(destination).use { outputStream ->
            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            var totalBytesRead: Long = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
              outputStream.write(buffer, 0, bytesRead)
              totalBytesRead += bytesRead
              val progress =
                if (contentLength > 0) {
                  (totalBytesRead.toFloat() / contentLength.toFloat()) * 100
                } else {
                  -1f
                }
              emit(progress)
            }
            outputStream.flush()
            emit(100f)
          }
        }
      }
    }.flowOn(Dispatchers.IO)

  fun getApkFile(release: Release): File? {
    // Return null if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return null
    }

    val asset = selectBestApkAsset(release.assets) ?: return null
    val file = File(context.externalCacheDir, asset.name)
    return if (file.exists()) file else null
  }

  fun clearCache() {
    // No-op if update feature is disabled
    if (!BuildConfig.ENABLE_UPDATE_FEATURE) {
      return
    }

    context.externalCacheDir?.listFiles()?.forEach {
      if (it.name.endsWith(".apk")) it.delete()
    }
  }

  private companion object {
    const val STABLE_RELEASE_URL = "https://api.github.com/repos/ZHINFINITY/Mpv-infinity/releases/latest"
    const val PREVIEW_RELEASE_URL = "https://raw.githubusercontent.com/ZHINFINITY/Mpv-infinity/main/latest.json"
    const val LEGACY_IGNORED_VERSION_KEY = "ignored_version"
    val PREVIEW_TAG_REGEX = Regex("""(?:preview-)?r(\d+)""", RegexOption.IGNORE_CASE)
    val SUPPORTED_ARCHITECTURES = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
  }
}
