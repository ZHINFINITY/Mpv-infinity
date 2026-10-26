/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.theme

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides whether this device may run Liquid Glass at all. When it may not, the app turns the
 * glass preference off at startup, so every surface takes its existing classic Material fallback
 * instead of crashing.
 *
 * Hard requirements: Android 12+ (RenderEffect blur), not a low-RAM device, OpenGL ES 3.0+.
 * Refraction additionally needs Android 13+ (checked separately by [RuntimeShaderSupport]).
 *
 * Crash memory: an uncaught exception whose stack runs through glass code, or a native crash
 * (GPU driver) while glass was still initialising in that process, disables glass on the next
 * launch. The user may switch it back on, which clears the record.
 */
object GlassDeviceSupport {
  private const val TAG = "GlassDeviceSupport"
  private const val PREFS = "liquid_glass_device_support"
  private const val KEY_CRASHED = "glass_crashed"
  private const val KEY_INIT_PENDING_PID = "glass_init_pending_pid"
  private const val MIN_SDK = 31
  private const val MIN_GLES = 0x30000

  /** How long glass must draw before the startup sentinel is cleared. */
  const val HEALTHY_AFTER_MS = 2_500L

  enum class Verdict(val hard: Boolean) {
    Supported(false),
    OldAndroid(true),
    LowRam(true),
    NoGles3(true),
    CrashedBefore(false),
  }

  data class DeviceFacts(
    val sdkInt: Int,
    val isLowRamDevice: Boolean,
    val glEsVersion: Int,
  )

  /** Pure: device facts plus crash memory to a verdict. */
  fun evaluate(facts: DeviceFacts, crashedBefore: Boolean): Verdict =
    when {
      facts.sdkInt < MIN_SDK -> Verdict.OldAndroid
      facts.isLowRamDevice -> Verdict.LowRam
      facts.glEsVersion in 1 until MIN_GLES -> Verdict.NoGles3
      crashedBefore -> Verdict.CrashedBefore
      else -> Verdict.Supported
    }

  private const val GLASS_CRASH_STACK_FRAME_LIMIT = 12
  private val glassClassMarkers = listOf(
    "android.graphics.RuntimeShader",
    "app.infinity.mpvz.ui.theme.AgslRuntimeShaders",
    "app.infinity.mpvz.ui.theme.RuntimeShaderSupport",
    "app.infinity.mpvz.ui.theme.GlassShaderCache",
  )

  /** Pure: whether a crash originated in the top frames of a specific glass shader path. */
  fun isGlassCrash(throwable: Throwable): Boolean =
    generateSequence(throwable) { it.cause }.take(8).any { error ->
      error.stackTrace.take(GLASS_CRASH_STACK_FRAME_LIMIT)
        .any { frame -> glassClassMarkers.any { frame.className.startsWith(it) } }
    }

  /**
   * Pure: a native crash of the very process that had armed the startup sentinel (glass was
   * initialising and had not yet drawn healthily) counts as a glass crash.
   */
  fun isNativeCrashDuringGlassInit(pendingPid: Int, lastExitPid: Int?, lastExitWasNativeCrash: Boolean): Boolean =
    pendingPid > 0 && lastExitWasNativeCrash && lastExitPid == pendingPid

  @Volatile private var cachedVerdict: Verdict? = null
  private val sentinelArmed = AtomicBoolean(false)

  fun verdict(context: Context): Verdict {
    cachedVerdict?.let { return it }
    val prefs = prefs(context)
    val crashed = prefs?.getBoolean(KEY_CRASHED, false) == true
    val facts = deviceFacts(context)
    return evaluate(facts, crashed).also { cachedVerdict = it }
  }

  fun allowsGlass(context: Context): Boolean = verdict(context) == Verdict.Supported

  /**
   * Called once from Application.onCreate before any UI: checks how the previous process ended
   * and, on an unsupported device, turns the glass preference off.
   */
  fun enforceAtStartup(context: Context, disableGlassPreference: () -> Unit) {
    runCatching { inspectPreviousExit(context) }
      .onFailure { error -> Log.w(TAG, "Could not read the previous exit reason", error) }
    cachedVerdict = null
    val verdict = verdict(context)
    if (verdict != Verdict.Supported) {
      Log.i(TAG, "Liquid Glass disabled on this device: $verdict")
      disableGlassPreference()
    }
  }

  /** From the global crash handler, before the process exits. Must not throw. */
  fun recordCrash(context: Context, throwable: Throwable) {
    runCatching {
      if (isGlassCrash(throwable)) {
        prefs(context)?.edit()?.putBoolean(KEY_CRASHED, true)?.commit()
      }
    }
  }

  /** The user switched glass back on: forget the crash record and try again. */
  fun clearCrashRecord(context: Context) {
    runCatching { prefs(context)?.edit()?.remove(KEY_CRASHED)?.remove(KEY_INIT_PENDING_PID)?.commit() }
    cachedVerdict = null
  }

  /** First glass draw in this process: arm the sentinel. Returns true if this call armed it. */
  fun armStartupSentinel(context: Context): Boolean {
    if (!sentinelArmed.compareAndSet(false, true)) return false
    runCatching { prefs(context)?.edit()?.putInt(KEY_INIT_PENDING_PID, android.os.Process.myPid())?.commit() }
    return true
  }

  /** Glass has drawn healthily for [HEALTHY_AFTER_MS]: a later crash is not an init crash. */
  fun disarmStartupSentinel(context: Context) {
    runCatching { prefs(context)?.edit()?.remove(KEY_INIT_PENDING_PID)?.apply() }
  }

  private fun inspectPreviousExit(context: Context) {
    val prefs = prefs(context) ?: return
    val pendingPid = prefs.getInt(KEY_INIT_PENDING_PID, 0)
    if (pendingPid <= 0) return
    var lastPid: Int? = null
    var nativeCrash = false
    if (Build.VERSION.SDK_INT >= 30) {
      val am = context.getSystemService(ActivityManager::class.java)
      val last = am?.getHistoricalProcessExitReasons(context.packageName, 0, 1)?.firstOrNull()
      lastPid = last?.pid
      nativeCrash = last?.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
    }
    val editor = prefs.edit().remove(KEY_INIT_PENDING_PID)
    if (isNativeCrashDuringGlassInit(pendingPid, lastPid, nativeCrash)) {
      Log.w(TAG, "Previous process crashed natively while Liquid Glass was initialising")
      editor.putBoolean(KEY_CRASHED, true)
    }
    editor.commit()
  }

  private fun deviceFacts(context: Context): DeviceFacts {
    val am = context.getSystemService(ActivityManager::class.java)
    return DeviceFacts(
      sdkInt = Build.VERSION.SDK_INT,
      isLowRamDevice = am?.isLowRamDevice == true,
      glEsVersion = runCatching { am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0 }.getOrDefault(0),
    )
  }

  private fun prefs(context: Context): SharedPreferences? =
    runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()

  @VisibleForTesting
  internal fun resetForTest() {
    cachedVerdict = null
    sentinelArmed.set(false)
  }
}

/** Settings summary for the Liquid Glass switch, per device verdict. */
object GlassDeviceSupportText {
  private const val BASE =
    "Shared sampled video glass on player controls, toggles and sheets. Android 13+ adds lens " +
      "refraction; Android 12 uses frosted samples. HDR, low memory and severe heat use a " +
      "readable translucent fallback. Sampling stops when controls are hidden."

  fun summary(verdict: GlassDeviceSupport.Verdict): String =
    when (verdict) {
      GlassDeviceSupport.Verdict.Supported -> BASE
      GlassDeviceSupport.Verdict.OldAndroid -> "Not supported on this device: Liquid Glass needs Android 12 or newer. The classic look is used."
      GlassDeviceSupport.Verdict.LowRam -> "Not supported on this device: it is marked as a low-memory device. The classic look is used."
      GlassDeviceSupport.Verdict.NoGles3 -> "Not supported on this device: its graphics driver lacks OpenGL ES 3.0. The classic look is used."
      GlassDeviceSupport.Verdict.CrashedBefore -> "Turned off after a graphics crash on this device. Switch it on to try again."
    }
}

/**
 * Arms the startup sentinel the first time glass draws in this process and clears it once glass
 * has drawn for [GlassDeviceSupport.HEALTHY_AFTER_MS]. A native GPU crash in between is
 * remembered at the next launch and glass is turned off.
 */
@androidx.compose.runtime.Composable
internal fun GlassStartupWatch(active: Boolean) {
  val context = androidx.compose.ui.platform.LocalContext.current
  androidx.compose.runtime.LaunchedEffect(active) {
    if (!active) return@LaunchedEffect
    val armed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      GlassDeviceSupport.armStartupSentinel(context)
    }
    if (!armed) return@LaunchedEffect
    try {
      androidx.compose.runtime.withFrameNanos { }
      kotlinx.coroutines.delay(GlassDeviceSupport.HEALTHY_AFTER_MS)
      androidx.compose.runtime.withFrameNanos { }
    } finally {
      GlassDeviceSupport.disarmStartupSentinel(context)
    }
  }
}
