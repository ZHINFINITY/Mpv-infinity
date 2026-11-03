/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.theme

import android.graphics.Bitmap
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    buffers.release(first)
    val second = buffers.acquire(16, 16)!!
    assertNotSame(first, second)
    buffers.markPublished(second)
    buffers.release(second)
    val third = buffers.acquire(16, 16)!!
    assertSame(first, third)
    buffers.release(third)
    buffers.recycle()
  }

  @Test fun frostWorkArraysAreReusedUntilSampleDimensionsChange() {
    val buffers = GlassCaptureBuffers()
    val first = buffers.acquire(16, 8)!!
    val pixels = buffers.pixels
    val frostOutput = buffers.frostOutput
    val frostScratch = buffers.frostScratch
    buffers.release(first)

    val sameSize = buffers.acquire(16, 8)!!
    assertSame(pixels, buffers.pixels)
    assertSame(frostOutput, buffers.frostOutput)
    assertSame(frostScratch, buffers.frostScratch)
    buffers.release(sameSize)

    val resized = buffers.acquire(32, 16)!!
    assertNotSame(pixels, buffers.pixels)
    assertNotSame(frostOutput, buffers.frostOutput)
    assertNotSame(frostScratch, buffers.frostScratch)
    assertEquals(32 * 16, buffers.frostOutput.size)
    buffers.release(resized)
    buffers.recycle()
    assertEquals(0, buffers.frostOutput.size)
    assertEquals(0, buffers.frostScratch.size)
  }

  @Test fun resizeAllocatesNewPairAndOldPublishedBitmapIsNotReused() {
    val buffers = GlassCaptureBuffers()
    val old = buffers.acquire(8, 8)!!
    buffers.markPublished(old)
    buffers.release(old)
    val resized = buffers.acquire(32, 32)!!
    assertNotSame(old, resized)
    assertEquals(32, resized.width)
    assertEquals(32, resized.height)
    assertTrue(old.isRecycled)
    buffers.release(resized)
    buffers.recycle()
  }

  @Test fun recycleRecyclesPublishedAndSpareCaptureScratch() {
    val buffers = GlassCaptureBuffers()
    val published = buffers.acquire(16, 16)!!
    val spare = buffers.acquire(16, 16)!!
    buffers.markPublished(published)
    buffers.release(published)
    buffers.release(spare)

    buffers.recycle()

    assertTrue(published.isRecycled)
    assertTrue(spare.isRecycled)
  }

  @Test fun resizeAndDisposeRecycleOnlyTheCaptureScratchPair() {
    val buffers = GlassCaptureBuffers()
    val published = buffers.acquire(8, 8)!!
    val spare = buffers.acquire(8, 8)!!
    buffers.markPublished(published)
    buffers.release(published)
    buffers.release(spare)
    val active = buffers.acquire(32, 32)!!

    assertTrue(published.isRecycled)
    assertTrue(spare.isRecycled)
    buffers.release(active)
    buffers.recycle()

    assertTrue(active.isRecycled)
  }

  @Test fun retiredScratchWaitsForLatePixelCopyCallbackAfterCallerLeaseEnds() {
    val buffers = GlassCaptureBuffers()
    val old = buffers.acquire(8, 8)!!
    buffers.markPublished(old)
    assertTrue(buffers.retain(old))
    // The owner lease ends on cancellation, but PixelCopy may still be writing until its callback.
    buffers.release(old)

    val resized = buffers.acquire(32, 32)!!

    assertFalse(old.isRecycled)
    assertEquals(1, buffers.retiredBitmapCount)
    buffers.release(old)
    assertTrue(old.isRecycled)
    assertEquals(0, buffers.retiredBitmapCount)
    buffers.release(resized)
    buffers.recycle()
  }

  @Test fun repeatedResizeBackpressuresBeforeRetiredPixelCopyBuffersExceedBound() {
    val buffers = GlassCaptureBuffers()
    val pending = mutableListOf<Bitmap>()
    var size = 8
    while (size <= 64) {
      val target = buffers.acquire(size, size) ?: break
      buffers.markPublished(target)
      assertTrue(buffers.retain(target))
      buffers.release(target)
      pending += target
      size += 8
    }

    assertEquals(GlassCaptureBuffers.MaxRetiredBitmapCount, buffers.retiredBitmapCount)
    assertTrue(buffers.retiredBitmapCount <= GlassCaptureBuffers.MaxRetiredBitmapCount)
    pending.forEach(buffers::release)
    assertTrue(pending.dropLast(1).all { it.isRecycled })
    assertFalse(pending.last().isRecycled)
    buffers.recycle()
    assertTrue(pending.last().isRecycled)
  }

  @Test fun immutableFrameSnapshotDoesNotShareCaptureBufferPixels() {
    val scratch = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val originalPixel = 0xFF123456.toInt()
    scratch.setPixel(0, 0, originalPixel)

    val snapshot = immutableGlassSnapshot(scratch)!!

    assertNotSame(scratch, snapshot)
    assertFalse(snapshot.isMutable)
    scratch.setPixel(0, 0, 0xFFABCDEF.toInt())
    scratch.recycle()
    // Recycling the mutable capture buffer must not invalidate the frame retained by Compose.
    assertEquals(originalPixel, snapshot.getPixel(0, 0))
    snapshot.recycle()
  }

  @Test fun disposalAddsAtMostTheFinalActivePairToTheRetiredBufferBound() {
    val buffers = GlassCaptureBuffers()
    val pending = mutableListOf<Bitmap>()
    var size = 8
    while (size <= 64) {
      val target = buffers.acquire(size, size) ?: break
      buffers.markPublished(target)
      assertTrue(buffers.retain(target))
      buffers.release(target)
      pending += target
      size += 8
    }
    val secondActive = buffers.acquire(size - 8, size - 8)!!
    assertTrue(buffers.retain(secondActive))
    buffers.release(secondActive)
    pending += secondActive

    assertEquals(GlassCaptureBuffers.MaxRetiredBitmapCount, buffers.retiredBitmapCount)
    buffers.recycle()
    assertEquals(GlassCaptureBuffers.MaxRetiredBitmapCountOnDispose, buffers.retiredBitmapCount)

    pending.forEach(buffers::release)
    assertEquals(0, buffers.retiredBitmapCount)
    assertTrue(pending.all { it.isRecycled })
  }
}
