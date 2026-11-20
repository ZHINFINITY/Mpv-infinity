/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlUtilsTest {
  @Test
  fun mixedCaseHttpsSchemeIsNormalizedWithoutChangingUriContents() {
    assertEquals(
      "https://demo.navidrome.org:4533/rest/stream.view?x=1",
      ServerUrlUtils.generateCandidateUrls(
        "Https://demo.navidrome.org:4533/rest/stream.view?x=1",
      ).single(),
    )
  }

  @Test
  fun mixedCaseHttpSchemeIsNormalizedWithoutChangingUriContents() {
    assertEquals(
      "http://192.168.1.23:4533/library?x=1",
      ServerUrlUtils.generateCandidateUrls(
        "HTTP://192.168.1.23:4533/library?x=1",
      ).single(),
    )
  }
}
