package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResolvedLoadResourceOwnerTest {
  @Test
  fun cancellationBeforeNativeCommitClosesFdAndReleasesPreparedRegistrationOnce() {
    var fdCloseCount = 0
    val released = mutableListOf<String>()
    val owner = ResolvedLoadResourceOwner(
      preparedRegistration = "prepared",
      closeUnconsumedFd = { fdCloseCount++ },
      releaseRegistration = { released += it },
    )

    owner.cleanupOnFailure()
    owner.cleanupOnFailure()

    assertEquals(1, fdCloseCount)
    assertEquals(listOf("prepared"), released)
  }

  @Test
  fun cancellationAfterSuccessfulCommitKeepsActiveRegistrationAndReleasesDisplacedOnce() {
    var fdCloseCount = 0
    val released = mutableListOf<String>()
    val owner = ResolvedLoadResourceOwner(
      preparedRegistration = "active",
      closeUnconsumedFd = { fdCloseCount++ },
      releaseRegistration = { released += it },
    )
    owner.markNativeCommitInvoked()
    owner.recordSuccessfulCommit(
      generation = 42L,
      displaced = "previous",
      displacedAuxiliary = listOf("auxiliary-a", "auxiliary-b"),
    )

    owner.cleanupOnFailure()
    owner.cleanupOnFailure()

    assertEquals(0, fdCloseCount)
    assertFalse("the newly active registration must remain owned", "active" in released)
    assertEquals(listOf("previous", "auxiliary-a", "auxiliary-b"), released)
  }

  @Test
  fun cancellationAfterNativeAttemptDoesNotCloseAnAmbiguousFd() {
    var fdCloseCount = 0
    val released = mutableListOf<String>()
    val owner = ResolvedLoadResourceOwner(
      preparedRegistration = "prepared",
      closeUnconsumedFd = { fdCloseCount++ },
      releaseRegistration = { released += it },
    )
    owner.markNativeCommitInvoked()

    owner.cleanupOnFailure()

    assertEquals(0, fdCloseCount)
    assertEquals(listOf("prepared"), released)
  }
}
