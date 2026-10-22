/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.preferences

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.infinity.mpvz.R
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real packaged-resource resolution; source-only checks cannot detect English fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppLanguageResourcesRuntimeTest {
  private fun context(tag: String): Context {
    val app = ApplicationProvider.getApplicationContext<Context>()
    return app.createConfigurationContext(Configuration(app.resources.configuration).apply {
      setLocale(Locale.forLanguageTag(tag))
    })
  }

  private fun assertSettingsAndPopupAreLocalized(tag: String) {
    val english = context("en")
    val localized = context(tag)
    for (id in listOf(R.string.pref_custom_theme_title, R.string.pref_custom_theme_summary,
      R.string.pref_custom_theme_create, R.string.pref_custom_theme_mute_video)) {
      val actual = localized.getString(id)
      assertTrue("$tag resource $id is empty", actual.isNotBlank())
      assertNotEquals("$tag settings/popup must not fall back to English", english.getString(id), actual)
    }
    val hue = localized.getString(R.string.pref_custom_theme_hue, 180)
    val opacity = localized.getString(R.string.pref_custom_theme_opacity_value, 75)
    assertFalse(hue.contains("%1\$d"))
    assertFalse(opacity.contains("%1\$d"))
    assertTrue("Literal percentage token must survive localization", opacity.contains('%'))
    assertEquals("#FFFFFFFF", localized.getString(R.string.pref_custom_theme_argb_placeholder))
  }

  @Test fun indonesianSettingsAndPopupResolveTheirOwnPack() { assertSettingsAndPopupAreLocalized("id") }
  @Test fun vietnameseSettingsAndPopupResolveTheirOwnPack() { assertSettingsAndPopupAreLocalized("vi") }
  @Test fun polishSettingsAndPopupResolveTheirOwnPack() { assertSettingsAndPopupAreLocalized("pl") }
  @Test fun tamilSettingsAndPopupResolveTheirOwnPack() { assertSettingsAndPopupAreLocalized("ta") }
  @Test fun persianSettingsAndPopupResolveTheirOwnPackAndDirection() {
    assertSettingsAndPopupAreLocalized("fa")
    assertEquals(View.LAYOUT_DIRECTION_RTL, context("fa").resources.configuration.layoutDirection)
  }
  @Test fun polishSecondsUseNativeOneFewAndManyGrammar() {
    val resources = context("pl").resources
    assertEquals("1 sekunda", resources.getQuantityString(R.plurals.seconds, 1, 1))
    assertEquals("2 sekundy", resources.getQuantityString(R.plurals.seconds, 2, 2))
    assertEquals("5 sekund", resources.getQuantityString(R.plurals.seconds, 5, 5))
    assertEquals("22 sekundy", resources.getQuantityString(R.plurals.seconds, 22, 22))
  }
  @Test fun tamilPluralResourceDoesNotFallBackToEnglish() {
    val actual = context("ta").resources.getQuantityString(R.plurals.seconds, 2, 2)
    assertNotEquals(context("en").resources.getQuantityString(R.plurals.seconds, 2, 2), actual)
    assertTrue(actual.any { it in '\u0B80'..'\u0BFF' })
  }
  @Test fun indonesianLegacyAndModernLanguageTagsResolveTheSameResources() {
    assertEquals(context("id").getString(R.string.pref_custom_theme_title),
      context("in").getString(R.string.pref_custom_theme_title))
  }
  @Test fun existingLanguagePacksAlsoResolveTheNewSettingsAndPopupKeys() {
    listOf("ar", "de", "es", "fr", "hi", "ja", "pt-BR", "ru", "zh-CN")
      .forEach(::assertSettingsAndPopupAreLocalized)
  }
}
