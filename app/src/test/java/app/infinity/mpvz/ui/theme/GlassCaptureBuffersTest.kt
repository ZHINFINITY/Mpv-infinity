/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.theme

import android.graphics.Bitmap
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Plain Application: this pure buffer test must not boot the app's Koin graph (already started by other suites).
@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class GlassCaptureBuffersTest {
  @Test fun publishedBitmapIsNeverReturnedForTheNextWrite() {
    val buffers = GlassCaptureBuffers()
    val first = buffers.acquire(16, 16)!!
    buffers.markPublished(first)
    val second = buffers.acquire(16, 16)!!
    assertNotSame(first, second)
    buffers.markPublished(second)
    val third = buffers.acquire(16, 16)!!
    assertSame(first, third)
    buffers.recycle()
  }

  @Test fun resizeAllocatesNewPairAndOldPublishedBitmapIsNotReused() {
    val buffers = GlassCaptureBuffers()
    val old = buffers.acquire(8, 8)!!
    buffers.markPublished(old)
    val resized = buffers.acquire(32, 32)!!
    assertNotSame(old, resized)
    assertEquals(32, resized.width)
    assertEquals(32, resized.height)
    buffers.recycle()
  }
}
