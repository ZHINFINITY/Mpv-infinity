/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.utils.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VolatileUrlKeyTest {
  @Test
  fun dropsRotatingCredentialsButKeepsStableParams() {
    val first =
      "http://192.168.1.9:8096/Items/abc/Images/Primary?maxWidth=400&quality=80&api_key=AAA111"
    val second =
      "http://192.168.1.9:8096/Items/abc/Images/Primary?maxWidth=400&quality=80&api_key=BBB222"
    val expected = "http://192.168.1.9:8096/Items/abc/Images/Primary?maxWidth=400&quality=80"
    assertEquals(expected, VolatileUrlKey.normalize(first))
    assertEquals(VolatileUrlKey.normalize(first), VolatileUrlKey.normalize(second))
  }

  @Test
  fun handlesEmbyTokenAndFragments() {
    assertEquals(
      "http://host/Videos/1/stream?static=true",
      VolatileUrlKey.normalize("http://host/Videos/1/stream?static=true&X-Emby-Token=zzz#t=10"),
    )
    assertEquals("http://host/Videos/1/stream", VolatileUrlKey.normalize("http://host/Videos/1/stream?api_key=zzz"))
  }

  @Test
  fun leavesPlainUrlsUntouched() {
    assertEquals("/sdcard/Movies/a.mkv", VolatileUrlKey.normalize("/sdcard/Movies/a.mkv"))
    assertEquals("http://host/a", VolatileUrlKey.normalize(" http://host/a?api_key=zzz "))
    assertEquals("http://host/a", VolatileUrlKey.normalize("http://host/a"))
  }
}
