/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.utils.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.infinity.mpvz.BuildConfig

object VulkanCapabilities {
  private const val TAG = "VulkanCapabilities"
  private const val MIN_OPEN_GL_ES_VERSION = 0x00030001
  private const val MIN_VULKAN_VERSION = 0x00403000

  val isBackendIncluded: Boolean
    get() = BuildConfig.MPV_SUPPORTS_VULKAN

  /** Returns whether this APK and the device can both use mpv's Vulkan renderer. */
  fun isAvailable(context: Context): Boolean = isBackendIncluded && isDeviceSupported(context)

  /** Returns whether the device meets Mpv∞'s Vulkan renderer requirements. */
  fun isDeviceSupported(context: Context): Boolean {
    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Log.d(TAG, "Vulkan unavailable: Android API ${Build.VERSION.SDK_INT} is below 33")
        return false
      }

      val packageManager = context.packageManager
      val configInfo = packageManager.systemAvailableFeatures.firstOrNull { it.name == null }
      val openGlEsVersion = configInfo?.reqGlEsVersion ?: 0
      if (openGlEsVersion < MIN_OPEN_GL_ES_VERSION) {
        Log.d(TAG, "Vulkan unavailable: OpenGL ES version is below 3.1")
        return false
      }

      val supported =
        packageManager.hasSystemFeature(
          PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
          MIN_VULKAN_VERSION,
        )
      Log.d(TAG, "Device Vulkan 1.3 support: $supported")
      return supported
    } catch (error: Exception) {
      Log.e(TAG, "Failed to check device Vulkan support", error)
      return false
    }
  }
}
