package app.infinity.mpvz.ui.browser.audiobooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookReadPermissionTest {
  @Test
  fun contentUrisRequirePersistedReadAccessForReopen() {
    assertFalse(hasDurableAudiobookReadAccess("content", persistedReadPermission = false))
    assertTrue(hasDurableAudiobookReadAccess("content", persistedReadPermission = true))
  }

  @Test
  fun localFileUrisDoNotRequireSafGrant() {
    assertTrue(hasDurableAudiobookReadAccess("file", persistedReadPermission = false))
    assertTrue(hasDurableAudiobookReadAccess(null, persistedReadPermission = false))
  }
}
