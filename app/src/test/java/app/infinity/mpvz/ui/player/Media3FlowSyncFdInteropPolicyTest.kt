/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowSyncFdInteropPolicyTest {
  @Test
  fun disablesNativeSyncFdHandoffOnObservedAndroid16VivoI2405() {
    assertFalse(Media3FlowSyncFdInteropPolicy.allowNativeSyncFdInterop(36, "vivo", "I2405"))
    assertFalse(Media3FlowSyncFdInteropPolicy.allowNativeSyncFdInterop(36, "VIVO", "i2405"))
    assertFalse(Media3FlowSyncFdInteropPolicy.allowNativeSyncFdInterop(37, "vivo", " I2405 "))
  }

  @Test
  fun leavesOtherDevicesAndEarlierAndroidVersionsOnTheExistingFastPath() {
    assertTrue(Media3FlowSyncFdInteropPolicy.allowNativeSyncFdInterop(35, "vivo", "I2405"))
    assertTrue(Media3FlowSyncFdInteropPolicy.allowNativeSyncFdInterop(36, "vivo", "I2405 Pro"))
    assertTrue(Media3FlowSyncFdInteropPolicy.allowNativeSyncFdInterop(36, "iQOO", "I2405"))
  }

  @Test
  fun nativeContextCreationReceivesThePolicyAndUsesItToGateSyncFdInterop() {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val player = File(root, "app/src/main/java/app/infinity/mpvz/ui/player")
    val sink = File(player, "Media3FlowVideoSink.kt").readText()
    val wrapper = File(player, "Media3FlowVulkanNative.kt").readText()
    val native = File(root, "app/src/main/cpp/media3flow_vulkan_jni.cpp").readText()

    assertEquals(2, Regex("nativeCreateContext\\(").findAll(sink).count())
    assertTrue(sink.contains("nativeSyncFdInteropAllowed,"))
    assertTrue(wrapper.contains("allowNativeSyncFdInterop: Boolean"))
    assertTrue(native.contains("jboolean allowNativeSyncFdInterop"))
    assertTrue(native.contains("initializeExternalSync(context, allowNativeSyncFdInterop == JNI_TRUE)"))
    assertTrue(native.contains("syncFdInteropPolicyAllowed &&"))
    assertTrue(native.contains("sync_fd_policy_allowed=%d"))
  }
}
