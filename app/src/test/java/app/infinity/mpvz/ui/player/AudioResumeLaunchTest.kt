/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner report (build 1906): restoring the last song showed "Could not load this link" and 00:01.
 * The log showed "Unable to resolve playable media URI: null" for
 * /storage/emulated/0/Music/Mix/#voyuitwaaien (jumpstyle version).flac.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AudioResumeLaunchTest {
  private val savedPath = "/storage/emulated/0/Music/Mix/#voyuitwaaien (jumpstyle version).flac"

  /** Platform behavior the old code tripped over: setType() after setData() drops the data. */
  @Test
  fun assigningTypeAfterDataLosesTheData() {
    val intent = Intent(Intent.ACTION_VIEW).apply {
      data = Uri.parse("content://media/external/audio/media/1")
      type = "audio/flac"
    }
    assertNull(intent.data)
  }

  @Test
  fun rawLocalPathKeepsDataTypeAndTheWholeFileName() {
    val intent = AudioResumeLaunch.applyTo(Intent(Intent.ACTION_VIEW), savedPath, "audio/flac")
    assertNotNull(intent.data)
    val data = intent.data!!
    assertEquals("file", data.scheme)
    // '#' must be part of the path, not a fragment.
    assertEquals(savedPath, data.path)
    assertNull(data.fragment)
    assertEquals("audio/flac", intent.type)
    assertEquals(savedPath, intent.getStringExtra(AudioResumeLaunch.EXTRA_LOCAL_MEDIA_PATH))
  }

  @Test
  fun missingMimeFallsBackToAudio() {
    val intent = AudioResumeLaunch.applyTo(Intent(Intent.ACTION_VIEW), savedPath, null)
    assertNotNull(intent.data)
    assertEquals("audio/*", intent.type)
  }

  @Test
  fun contentUriStaysAsIsAndDropsAStaleLocalPath() {
    val source = Intent(Intent.ACTION_VIEW).putExtra(AudioResumeLaunch.EXTRA_LOCAL_MEDIA_PATH, "/storage/old.mp3")
    val intent = AudioResumeLaunch.applyTo(source, "content://media/external/audio/media/42", "", fallbackMimeType = null)
    assertEquals("content://media/external/audio/media/42", intent.dataString)
    assertNull(intent.type)
    assertFalse(intent.hasExtra(AudioResumeLaunch.EXTRA_LOCAL_MEDIA_PATH))
  }
}
