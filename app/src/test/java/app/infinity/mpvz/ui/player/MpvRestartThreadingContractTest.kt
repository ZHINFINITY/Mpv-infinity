/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvRestartThreadingContractTest {
  private fun source(name: String): String {
    val path = "src/main/java/app/infinity/mpvz/ui/player/$name.kt"
    return listOf(File(path), File("app/$path")).first { it.isFile }.readText()
  }

  @Test fun allSevenNativeCallbacksUseTheAdmissionFenceAndFinallyRelease() {
    val session = source("PlaybackSession")
    assertTrue(Regex("(?m)^    deliverNativeCallback \\{").findAll(session).count() == 7)
    val helper = session.substringAfter("private inline fun deliverNativeCallback").substringBefore("  override fun eventProperty")
    assertTrue(helper.contains("nativeCallbackGate.tryEnter()"))
    assertTrue(helper.contains("finally") && helper.contains("nativeCallbackGate.leave()"))
    val retire = session.substringAfter("private fun retireCoreForLifecycleChange()").substringBefore("  val isInitialized")
    assertTrue(retire.indexOf("nativeCallbackGate.closeAndDrain()") < retire.indexOf("MPVLib.destroy()"))
    assertFalse(session.contains("commitOutsideNativeLock"))
    assertFalse(session.contains("assumeNativeLock"))
  }

  @Test fun realRestartUsesWorkerButSurfaceAndDisplayBindingsStayOnMain() {
    val activity = source("PlayerActivity")
    val restart = activity.substringAfter("private fun requestMpvInterpolationRestart()").substringBefore("\n  private ")
    assertTrue(activity.contains("mpvInterpolationNativeWork = MpvNativeWork()"))
    assertTrue(restart.contains("val init = mpvInterpolationNativeWork.execute"))
    assertTrue(restart.contains("val generation = mpvInterpolationNativeWork.execute"))
    assertTrue(restart.contains("withContext(Dispatchers.Main.immediate)"))
    assertTrue(restart.contains("player.snapshotDisplayRefreshRateOnMain()"))
    assertTrue(restart.contains("player.bindSessionCallbacksOnMain()"))
    assertFalse(restart.contains("setupMPV()"))
    val worker = source("MpvNativeWork")
    assertTrue(worker.contains("CoroutineDispatcher = Dispatchers.IO"))
    assertTrue(worker.contains("withContext(dispatcher)") && worker.contains("serial.withLock"))
    val view = source("MPVView")
    val hints = view.substringAfter("internal fun updateSurfaceFrameRateHint()").substringBefore("private val observedProps")
    assertTrue(hints.contains("if (Looper.myLooper() != Looper.getMainLooper()) return"))
  }

  @Test fun generationIsPublishedBeforeNativeLoadCanEmitCallbacks() {
    val session = source("PlaybackSession")
    val load = session.substringAfter("  private fun load(")
    val reserved = load.indexOf("onGenerationReserved?.invoke(generation)")
    val nativeLoad = load.indexOf("MPVLib.command(\"loadfile\"")
    assertTrue("actual loadfile command must be present", nativeLoad >= 0)
    assertTrue("generation must be visible before a native event can race", reserved >= 0 && reserved < nativeLoad)
    val restart = source("PlayerActivity").substringAfter("private fun requestMpvInterpolationRestart()")
    assertTrue(restart.contains("onGenerationReserved = { token ->"))
    assertTrue(restart.contains("currentItemAbsent = session.currentItem == null"))
    assertTrue(restart.contains("coreReplacementStarted = coreReplacementStarted"))
  }
}
