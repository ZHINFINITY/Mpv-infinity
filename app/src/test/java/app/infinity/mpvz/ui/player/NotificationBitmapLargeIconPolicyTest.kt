/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationBitmapLargeIconPolicyTest {
  @Test
  fun omitsBitmapLargeIconsOnTheTwoObservedAndroid16CrashDevices() {
    assertTrue(NotificationBitmapLargeIconPolicy.shouldOmitBitmapLargeIcon(36, "Xiaomi", "25053PC47I"))
    assertTrue(NotificationBitmapLargeIconPolicy.shouldOmitBitmapLargeIcon(36, "vivo", "I2405"))
    assertTrue(NotificationBitmapLargeIconPolicy.shouldOmitBitmapLargeIcon(37, " VIVO ", " i2405 "))
  }

  @Test
  fun keepsBitmapLargeIconsOnOtherDevicesAndEarlierAndroidVersions() {
    assertFalse(NotificationBitmapLargeIconPolicy.shouldOmitBitmapLargeIcon(35, "Xiaomi", "25053PC47I"))
    assertFalse(NotificationBitmapLargeIconPolicy.shouldOmitBitmapLargeIcon(36, "Xiaomi", "other-model"))
    assertFalse(NotificationBitmapLargeIconPolicy.shouldOmitBitmapLargeIcon(36, "vivo", "I2405 Pro"))
    assertFalse(NotificationBitmapLargeIconPolicy.shouldOmitBitmapLargeIcon(36, "iQOO", "I2405"))
  }

  @Test
  fun bothNotificationStylesUseThePolicyWithoutDroppingMediaSessionArtwork() {
    val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
    val service = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/MediaPlaybackService.kt").readText()

    assertEquals(2, Regex("setLargeIcon\\(notificationLargeIcon\\(\\)\\)").findAll(service).count())
    assertTrue(service.contains("NotificationBitmapLargeIconPolicy.shouldOmitBitmapLargeIcon("))
    assertTrue(service.contains("metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)"))
  }
}
