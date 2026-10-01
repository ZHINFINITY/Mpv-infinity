package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundArtworkRequestGuardTest {
  @Test
  fun lookupWaitsUntilTheCurrentMediaIsReady() {
    val guard = BackgroundArtworkRequestGuard()

    assertNull(guard.begin("item-a|cover-a", mediaReady = false))
    val request = guard.begin("item-a|cover-a", mediaReady = true)

    assertNotNull(request)
    assertTrue(guard.isCurrent(request!!, "item-a|cover-a", mediaReady = true))
    assertFalse(guard.isCurrent(request, "item-a|cover-a", mediaReady = false))
  }

  @Test
  fun identicalInFlightKeyIsNotRestartedAndOutOfOrderResultCannotPublish() {
    val guard = BackgroundArtworkRequestGuard()
    val first = guard.begin("item-a|cover-a", mediaReady = true)!!

    assertNull(guard.begin("item-a|cover-a", mediaReady = true))

    val latest = guard.begin("item-b|cover-b", mediaReady = true)!!
    assertFalse(guard.isCurrent(first, "item-b|cover-b", mediaReady = true))

    guard.complete(first, settled = true, hasArtwork = true)
    assertTrue(guard.isCurrent(latest, "item-b|cover-b", mediaReady = true))
    assertNull(guard.begin("item-b|cover-b", mediaReady = true))
    assertFalse(guard.hasReadyArtwork("item-a|cover-a"))
  }

  @Test
  fun nullArtworkSettlesOnlyItsKeyAndAChangedArtKeyCanRetry() {
    val guard = BackgroundArtworkRequestGuard()
    val request = guard.begin("item-a|cover-a", mediaReady = true)!!

    guard.complete(request, settled = true, hasArtwork = false)

    assertNull(guard.begin("item-a|cover-a", mediaReady = true))
    assertFalse(guard.hasReadyArtwork("item-a|cover-a"))
    assertNotNull(guard.begin("item-a|cover-b", mediaReady = true))
  }

  @Test
  fun currentNonNullArtworkIsMarkedReady() {
    val guard = BackgroundArtworkRequestGuard()
    val request = guard.begin("item-a|cover-a", mediaReady = true)!!

    guard.complete(request, settled = true, hasArtwork = true)

    assertTrue(guard.hasReadyArtwork("item-a|cover-a"))
    assertFalse(guard.hasReadyArtwork("item-b|cover-b"))
  }

  @Test
  fun staleCompletionCannotClearAnAbaQueueRequest() {
    val guard = BackgroundArtworkRequestGuard()
    val oldA = guard.begin("item-a|cover-a", mediaReady = true)!!
    guard.begin("item-b|cover-b", mediaReady = true)
    val currentA = guard.begin("item-a|cover-a", mediaReady = true)!!

    guard.complete(oldA, settled = true, hasArtwork = false)

    assertTrue(guard.isCurrent(currentA, "item-a|cover-a", mediaReady = true))
  }
}
