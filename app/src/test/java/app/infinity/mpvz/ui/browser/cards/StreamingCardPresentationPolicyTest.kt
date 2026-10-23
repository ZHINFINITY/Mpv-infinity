package app.infinity.mpvz.ui.browser.cards

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

class StreamingCardPresentationPolicyTest {
  @Test
  fun uriHostIsShownWithoutUserInfoPathOrQueryToken() {
    val label = StreamingCardPresentationPolicy.safeSourceLabel(
      "https://user:password@example.com/live/channel.m3u8?token=secret",
    )
    assertEquals("example.com", label)
    assertFalse(label.contains("password"))
    assertFalse(label.contains("secret"))
    assertFalse(label.contains("/live"))
  }

  @Test
  fun customSchemeUsesHostAndInvalidValueIsGeneric() {
    assertEquals("radio.example", StreamingCardPresentationPolicy.safeSourceLabel("radio+tls://radio.example:8443/live"))
    assertEquals("Stream source", StreamingCardPresentationPolicy.safeSourceLabel("://bad/user:pass?token=secret"))
  }

  @Test
  fun localAndProviderLabelsRemainSafe() {
    assertEquals("file stream", StreamingCardPresentationPolicy.safeSourceLabel("file:///private/secret.m3u"))
    assertEquals("Acme Radio", StreamingCardPresentationPolicy.safeSourceLabel("https://secret.example/a?token=1", " Acme Radio "))
  }
}
