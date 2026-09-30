package app.infinity.mpvz.catalog

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSourceLoadingTest {
  @Test
  fun approvedBuiltInIsDefaultAndSeparateUserAddonsRemainAlongsideIt() {
    val userOne = CatalogSource("user-one", "One", "https://catalog-one.invalid")
    val userTwo = CatalogSource("user-two", "Two", "http://catalog-two.invalid")

    val sources = includeDefaultBuiltinCatalogSource(listOf(userOne, userTwo))

    assertEquals(DEFAULT_BUILTIN_CATALOG_SOURCE, sources.first())
    assertEquals(BUILTIN_CATALOG_SOURCE_ID, sources.first().id)
    assertEquals("https://v3-cinemeta.strem.io/manifest.json", sources.first().manifestUrl)
    assertEquals(listOf(DEFAULT_BUILTIN_CATALOG_SOURCE, userOne, userTwo), sources)
    assertEquals(sources, requestableCatalogSources(sources))
  }

  @Test
  fun invalidBuiltInPlaceholdersAndDisabledAddonsAreNotRequestable() {
    val placeholder = CatalogSource("builtin-placeholder", "Built-in", "")
    val spoofedBuiltin = CatalogSource("builtin-other", "Other", "https://unapproved.invalid/manifest.json")
    val hostless = CatalogSource("hostless", "Hostless", "https:/missing-host")
    val userOne = CatalogSource("user-one", "One", "https://catalog-one.invalid")
    val disabled = CatalogSource("disabled", "Disabled", "https://disabled.invalid", isEnabled = false)

    val sources = includeDefaultBuiltinCatalogSource(listOf(placeholder, spoofedBuiltin, hostless, userOne, disabled))
    val requestable = requestableCatalogSources(sources)

    assertEquals(listOf(DEFAULT_BUILTIN_CATALOG_SOURCE, userOne), requestable)
    assertFalse(isHttpAddonEndpoint(""))
    assertFalse(isHttpAddonEndpoint("https:/missing-host"))
    assertTrue(isHttpAddonEndpoint(userOne.manifestUrl))
  }

  @Test
  fun everyConfiguredAddonLoadsEvenWhenOneFailsAndSourcesRunConcurrently() = runBlocking {
    val userOne = CatalogSource("user-one", "One", "https://catalog-one.invalid")
    val userTwo = CatalogSource("user-two", "Two", "https://catalog-two.invalid")
    val sources = requestableCatalogSources(includeDefaultBuiltinCatalogSource(listOf(userOne, userTwo)))
    val started = Collections.synchronizedList(mutableListOf<String>())
    val allStarted = CompletableDeferred<Unit>()

    val results = loadCatalogSourcesIndependently(sources) { source ->
      started += source.id
      if (started.size == sources.size) allStarted.complete(Unit)
      withTimeout(2_000L) { allStarted.await() }
      if (source.id == userOne.id) error("one source is offline")
      "loaded:${source.id}"
    }

    assertEquals(sources.map { it.id }.toSet(), started.toSet())
    assertTrue(results.single { it.first == userOne }.second.isFailure)
    assertEquals("loaded:${userTwo.id}", results.single { it.first == userTwo }.second.getOrNull())
    assertNotNull(results.single { it.first == DEFAULT_BUILTIN_CATALOG_SOURCE }.second.getOrNull())
  }
}
