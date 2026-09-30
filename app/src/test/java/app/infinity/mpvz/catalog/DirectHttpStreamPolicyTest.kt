package app.infinity.mpvz.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectHttpStreamPolicyTest {
  @Test
  fun acceptsAbsoluteHttpAndHttpsStreamsWithHosts() {
    assertTrue(isDirectHttpStreamUrl("https://media.example.test/watch?id=1"))
    assertTrue(isDirectHttpStreamUrl("HTTP://media.example.test/video"))
  }

  @Test
  fun rejectsRelativeInvalidAndNonHttpUris() {
    assertFalse(isDirectHttpStreamUrl("/watch/1"))
    assertFalse(isDirectHttpStreamUrl("https:///missing-host"))
    assertFalse(isDirectHttpStreamUrl("file:///media/audio.m4b"))
    assertFalse(isDirectHttpStreamUrl("javascript:play()"))
  }
}
