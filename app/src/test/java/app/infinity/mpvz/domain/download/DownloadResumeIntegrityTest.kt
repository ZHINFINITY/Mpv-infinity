package app.infinity.mpvz.domain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResumeIntegrityTest {
  @Test
  fun sameStrongEtagAllowsAppendingOnlyTheValidatedSuffix() {
    val saved = DownloadValidators.fromResponse("\"v1\"", "Tue, 15 Nov 1994 08:12:31 GMT")
    assertEquals("\"v1\"", saved.ifRange)
    assertTrue(saved.matches("\"v1\"", "Tue, 15 Nov 1994 08:12:31 GMT"))
    assertEquals(DownloadRange(4, 7, 8), parseResumedRange("bytes 4-7/8", 4, 4))
  }

  @Test
  fun changedOrWeakValidatorCannotAuthorizeAppend() {
    val saved = DownloadValidators.fromResponse("\"v1\"", null)
    assertFalse(saved.matches("\"v2\"", null))
    assertNull(DownloadValidators.fromResponse("W/\"v1\"", null).ifRange)
    assertNull(DownloadValidators.fromResponse("*", null).ifRange)
  }

  @Test
  fun missingValidatorAndInvalidLastModifiedAreNeverTrusted() {
    assertNull(DownloadValidators.fromResponse(null, "not an HTTP date").ifRange)
    assertNull(DownloadValidators().ifRange)
  }

  @Test
  fun malformedFullOrOffsetRangeIsRejectedInsteadOfAppending() {
    assertNull(parseResumedRange("bytes 0-3/8", 4, 4))
    assertNull(parseResumedRange("bytes 4-7/*", 4, 4))
    assertNull(parseResumedRange("bytes 4-7/8", 4, 3))
    assertNull(parseResumedRange("not-a-range", 4, 4))
  }
}
