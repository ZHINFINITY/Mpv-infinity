/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import android.app.Activity
import android.app.Application
import android.media.AudioManager
import android.os.Bundle
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.ref.WeakReference

/**
 * LeakCanary (OnePlus, API 37), reported twice:
 * AudioManager.mAudioFocusIdListenerMap -> AudioFocusRequest.mFocusListener ->
 * PlayerActivity$$ExternalSyntheticLambda38 -> destroyed PlayerActivity, 1.5-1.8 MB per closed player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AudioFocusLeakTest {
  /** Stand-in for AudioManager's process-lifetime listener map, which outlives any Activity. */
  private val frameworkListenerMap = mutableListOf<AudioManager.OnAudioFocusChangeListener>()

  /** Mirrors PlayerActivity: the handler captures the Activity; only [listener] is handed out. */
  class WeakFocusActivity : Activity() {
    val payload = ByteArray(512 * 1024)
    var lastChange = 0
    private val handler = AudioFocusChangeHandler { change -> lastChange = change + payload.size * 0 }
    val listener = WeakAudioFocusListener(handler)
  }

  /** The old shape: a listener lambda that captures the Activity directly. */
  class CapturingFocusActivity : Activity() {
    val payload = ByteArray(512 * 1024)
    var lastChange = 0
    val listener = AudioManager.OnAudioFocusChangeListener { change -> lastChange = change + payload.size * 0 }
  }

  @After fun clearMap() = frameworkListenerMap.clear()

  private fun <T : Activity> createRegisterAndDestroy(type: Class<T>, listenerOf: (T) -> AudioManager.OnAudioFocusChangeListener): WeakReference<T> {
    val controller = Robolectric.buildActivity(type).create(Bundle()).start().resume()
    val activity = controller.get()
    frameworkListenerMap += listenerOf(activity)
    controller.pause().stop().destroy()
    assertTrue(activity.isDestroyed)
    return WeakReference(activity)
  }

  private fun collect(ref: WeakReference<*>): Boolean {
    repeat(20) {
      if (ref.get() == null) return true
      System.gc()
      System.runFinalization()
      @Suppress("UNUSED_VARIABLE") val pressure = ByteArray(4 * 1024 * 1024)
      Thread.sleep(20)
    }
    return ref.get() == null
  }

  @Test
  fun destroyedActivityIsCollectableWhileAudioManagerStillHoldsItsListener() {
    val ref = createRegisterAndDestroy(WeakFocusActivity::class.java) { it.listener }
    assertTrue("destroyed Activity must be collectable even if focus was never abandoned", collect(ref))
    val retained = frameworkListenerMap.single() as WeakAudioFocusListener
    assertTrue(retained.isDetached)
    // A late callback from the framework is a harmless no-op.
    retained.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
  }

  /** Control: proves this test would have caught the reported leak. */
  @Test
  fun capturingLambdaKeepsTheDestroyedActivity() {
    val ref = createRegisterAndDestroy(CapturingFocusActivity::class.java) { it.listener }
    assertFalse(collect(ref))
    assertNotNull(ref.get())
  }

  @Test
  fun liveActivityStillReceivesFocusChanges() {
    val controller = Robolectric.buildActivity(WeakFocusActivity::class.java).create().start().resume()
    controller.get().listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    assertTrue(controller.get().lastChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    assertFalse(controller.get().listener.isDetached)
    controller.pause().stop().destroy()
  }

  @Test
  fun abandonDependsOnSubmissionNotOnHeldFocus() {
    // After the Mini Player service takes focus, the Activity no longer "holds" it, but its
    // listener is still registered: the abandon must still run.
    assertTrue(AudioFocusRegistrationPolicy.mustAbandon(requestSubmitted = true))
    assertFalse(AudioFocusRegistrationPolicy.mustAbandon(requestSubmitted = false))
  }

  @Test
  fun onlyAFocusHoldingConfigRecreationKeepsTheRegistration() {
    assertTrue(AudioFocusRegistrationPolicy.releaseOnDestroy(changingConfigurations = false, holdsFocus = true))
    assertTrue(AudioFocusRegistrationPolicy.releaseOnDestroy(changingConfigurations = false, holdsFocus = false))
    assertTrue(AudioFocusRegistrationPolicy.releaseOnDestroy(changingConfigurations = true, holdsFocus = false))
    assertFalse(AudioFocusRegistrationPolicy.releaseOnDestroy(changingConfigurations = true, holdsFocus = true))
  }

  // PlayerActivity itself: every exit path (finish, Back, minimize handoff, engine switch, process
  // restore) goes through onDestroy, and the release there runs before any keep-alive branch.
  private val activitySource: String by lazy {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    File(root, "app/infinity/mpvz/ui/player/PlayerActivity.kt").readText()
  }

  private fun body(signature: String): String {
    val start = activitySource.indexOf(signature)
    assertTrue("missing $signature", start >= 0)
    return activitySource.substring(start, activitySource.indexOf("\n  }\n", start))
  }

  @Test
  fun playerActivityNeverHandsAudioManagerAnActivityCapturingListener() {
    assertFalse(activitySource.contains("AudioManager.OnAudioFocusChangeListener {"))
    assertTrue(activitySource.contains("private val audioFocusChangeListener = WeakAudioFocusListener(audioFocusChangeHandler)"))
    assertTrue(body("private fun setupAudio(").contains(".setOnAudioFocusChangeListener(audioFocusChangeListener)"))
    val request = body("override fun requestAudioFocus(")
    assertTrue(request.indexOf("audioFocusRequestSubmitted = true") in 0 until request.indexOf("audioManager.requestAudioFocus(req)"))
  }

  @Test
  fun onDestroyReleasesFocusBeforeAnyKeepAliveBranch() {
    val destroy = body("override fun onDestroy(")
    val release = destroy.indexOf("releaseAudioFocusRegistration()")
    assertTrue(release >= 0)
    assertTrue("release must not depend on the keep-alive decision", release < destroy.indexOf("runCatching {"))
    assertNull(Regex("""if \(!?keepBackgroundPlaybackAlive[^\n]*\n[^\n]*releaseAudioFocusRegistration""").find(destroy))
    assertTrue(body("override fun abandonAudioFocus(").contains("releaseAudioFocusRegistration()"))
  }
}
