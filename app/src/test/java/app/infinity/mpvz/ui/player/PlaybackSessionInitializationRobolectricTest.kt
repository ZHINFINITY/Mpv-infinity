/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.ui.player

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import `is`.xyz.mpv.MPVLib

/**
 * Exercises PlaybackSession's real admission helpers without loading libmpv.so.
 * The shadow deliberately records the bridge calls rather than replacing
 * PlaybackSession, so a regression in withCore/withReadyCore fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [35],
  application = Application::class,
  shadows = [ShadowPlaybackMpvLib::class],
  instrumentedPackages = ["is.xyz.mpv"],
)
class PlaybackSessionInitializationRobolectricTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Before fun resetCore() {
    PlaybackSession.destroy()
    ShadowPlaybackMpvLib.reset()
  }

  @After fun retireCore() {
    PlaybackSession.destroy()
  }

  @Test
  fun repeatedInitializationKeepsRendererAndFlowOptionsAndPropertyAccessAlive() {
    val createsBefore = ShadowPlaybackMpvLib.createCount
    val initsBefore = ShadowPlaybackMpvLib.initCount
    val destroysBefore = ShadowPlaybackMpvLib.destroyCount
    ShadowPlaybackMpvLib.clearRecords()
    val first = initialize("first")
    assertTrue(first.getOrThrow())
    val second = initialize("second")
    assertTrue(second.getOrThrow())
    val third = initialize("third")
    assertTrue(third.getOrThrow())

    assertEquals(createsBefore + 3, ShadowPlaybackMpvLib.createCount)
    assertEquals(initsBefore + 3, ShadowPlaybackMpvLib.initCount)
    assertEquals(destroysBefore + 2, ShadowPlaybackMpvLib.destroyCount)
    assertEquals(
      listOf("mediacodec-copy", "mediacodec"),
      ShadowPlaybackMpvLib.optionValues("hwdec").distinct(),
    )
    assertEquals("vulkan", ShadowPlaybackMpvLib.lastOption("gpu-api"))
    assertEquals("androidvk", ShadowPlaybackMpvLib.lastOption("gpu-context"))
    assertEquals("gpu-next", ShadowPlaybackMpvLib.lastOption("vo"))
    assertEquals("yes", ShadowPlaybackMpvLib.lastOption("mpvflow"))
    assertEquals("120", ShadowPlaybackMpvLib.lastOption("mpvflow-target-fps"))
    assertEquals("no", ShadowPlaybackMpvLib.lastOption("mpvflow-soft-blend"))
    assertEquals("post-value", ShadowPlaybackMpvLib.property("post-property"))
    assertEquals("post-value", PlaybackSession.getPropertyString("post-property"))
    assertTrue(ShadowPlaybackMpvLib.addObserverCount >= 1)
  }

  @Test
  fun onlyLifecycleOwnerMayWriteWhileInitializingAndCallbacksStayClosedUntilReady() {
    ShadowPlaybackMpvLib.clearRecords()
    val outsiderResult = AtomicInteger(99)
    val outsiderStarted = CountDownLatch(1)
    val releaseOwner = CountDownLatch(1)
    val outcome = AtomicReference<Result<Boolean>>()
    val result = thread(start = true, name = "playback-initializer") {
      outcome.set(PlaybackSession.initialize(
        context = context,
        configDir = "unused/config",
        cacheDir = "unused/cache",
        coreConfigurationKey = "owner-test-${System.nanoTime()}",
        initOptions = {
          assertEquals(0, PlaybackSession.setOptionString("owner-option", "accepted"))
          assertFalse(ShadowPlaybackMpvLib.callbackObserverRegistered)
          outsiderStarted.countDown()
          assertTrue(releaseOwner.await(10, TimeUnit.SECONDS))
        },
        postInitOptions = {
          assertNotNull(PlaybackSession.getPropertyString("user-agent"))
          PlaybackSession.setPropertyString("post-property", "post-value")
          assertEquals("post-value", PlaybackSession.getPropertyString("post-property"))
          assertFalse(ShadowPlaybackMpvLib.callbackObserverRegistered)
        },
        observeProperties = {},
      ))
    }
    try {
      assertTrue(outsiderStarted.await(10, TimeUnit.SECONDS))
      outsiderResult.set(PlaybackSession.setOptionString("outsider-option", "denied"))
      assertEquals(-1, outsiderResult.get())
      assertNull(ShadowPlaybackMpvLib.lastOption("outsider-option"))
    } finally {
      releaseOwner.countDown()
      result.join(10_000)
    }
    assertFalse(result.isAlive)
    assertTrue(outcome.get().getOrThrow())
    assertTrue(ShadowPlaybackMpvLib.callbackObserverRegistered)
    assertEquals("accepted", ShadowPlaybackMpvLib.lastOption("owner-option"))
  }

  private fun initialize(key: String) = PlaybackSession.initialize(
    context = context,
    configDir = "unused/config",
    cacheDir = "unused/cache",
    coreConfigurationKey = key,
    initOptions = {
      PlaybackSession.setOptionString("hwdec", if (key == "first") "mediacodec-copy" else "mediacodec")
      PlaybackSession.setOptionString("gpu-api", "vulkan")
      PlaybackSession.setOptionString("gpu-context", "androidvk")
      PlaybackSession.setOptionString("vo", "gpu-next")
      PlaybackSession.setOptionString("mpvflow", "yes")
      PlaybackSession.setOptionString("mpvflow-target-fps", "120")
      PlaybackSession.setOptionString("mpvflow-soft-blend", "no")
    },
    postInitOptions = {
      PlaybackSession.setPropertyString("post-property", "post-value")
      assertEquals("post-value", PlaybackSession.getPropertyString("post-property"))
    },
    observeProperties = {},
  )
}

@Implements(MPVLib::class, isInAndroidSdk = false)
@Suppress("unused")
class ShadowPlaybackMpvLib {
  companion object {
    private val options = linkedMapOf<String, MutableList<String>>()
    private val properties = linkedMapOf<String, String>()
    var createCount = 0
    var initCount = 0
    var destroyCount = 0
    var addObserverCount = 0
    var callbackObserverRegistered = false

    fun reset() {
      options.clear()
      properties.clear()
      createCount = 0
      initCount = 0
      destroyCount = 0
      addObserverCount = 0
      callbackObserverRegistered = false
    }

    fun clearRecords() {
      options.clear()
      properties.clear()
      callbackObserverRegistered = false
    }

    fun optionValues(name: String): List<String> = options[name].orEmpty()
    fun lastOption(name: String): String? = options[name]?.lastOrNull()
    fun property(name: String): String? = properties[name]

    /** MPVLib's real static block loads mpv/player; never execute it in local JVM tests. */
    @JvmStatic @Implementation
    fun __staticInitializer__() {
      // Suppressing <clinit> also suppresses Kotlin's INSTANCE assignment. Restore
      // only the singleton receiver; every bridge entry point used here is shadowed.
      // Robolectric instrumentation removes final from this field for its sandbox.
      ReflectionHelpers.setStaticField(
        MPVLib::class.java,
        "INSTANCE",
        ReflectionHelpers.callConstructor(MPVLib::class.java),
      )
    }

    @JvmStatic @Implementation
    fun addObserver(observer: MPVLib.EventObserver) { addObserverCount++; callbackObserverRegistered = true }

    @JvmStatic @Implementation
    fun removeObserver(observer: MPVLib.EventObserver) { callbackObserverRegistered = false }
  }

  @Implementation fun create(context: Context) { createCount++ }
  @Implementation fun init() { initCount++; properties["user-agent"] = "shadow-agent" }
  @Implementation fun destroy() { destroyCount++; callbackObserverRegistered = false }
  @Implementation fun setOptionString(name: String, value: String): Int {
    options.getOrPut(name) { mutableListOf() }.add(value)
    return 0
  }
  @Implementation fun setPropertyString(name: String, value: String) { properties[name] = value }
  @Implementation fun getPropertyString(name: String): String? = properties[name]
  @Implementation fun setPropertyInt(name: String, value: Int) { properties[name] = value.toString() }
  @Implementation fun setPropertyBoolean(name: String, value: Boolean) { properties[name] = value.toString() }
  @Implementation fun setPropertyDouble(name: String, value: Double) { properties[name] = value.toString() }
  @Implementation fun getPropertyInt(name: String): Int? = properties[name]?.toIntOrNull()
  @Implementation fun getPropertyBoolean(name: String): Boolean? = properties[name]?.toBooleanStrictOrNull()
  @Implementation fun getPropertyDouble(name: String): Double? = properties[name]?.toDoubleOrNull()
  @Implementation fun observeProperty(name: String, format: Int) {}
  @Implementation fun detachSurface() {}
}
