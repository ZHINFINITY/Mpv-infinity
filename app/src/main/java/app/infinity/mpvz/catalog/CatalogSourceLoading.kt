package app.infinity.mpvz.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale

internal const val BUILTIN_CATALOG_SOURCE_ID = "builtin-cinemeta"
internal const val BUILTIN_CATALOG_MANIFEST_URL = "https://v3-cinemeta.strem.io/manifest.json"
internal val DEFAULT_BUILTIN_CATALOG_SOURCE = CatalogSource(
  id = BUILTIN_CATALOG_SOURCE_ID,
  name = "Cinemeta",
  manifestUrl = BUILTIN_CATALOG_MANIFEST_URL,
)

internal const val BUILT_IN_CATALOG_UNAVAILABLE_MESSAGE =
  "No enabled catalog source. Enable a configured catalog add-on or add one in Stream settings."

internal fun isDefaultBuiltinCatalogSource(source: CatalogSource): Boolean =
  source.id == BUILTIN_CATALOG_SOURCE_ID &&
    source.manifestUrl.trim().equals(BUILTIN_CATALOG_MANIFEST_URL, ignoreCase = true)

/** Add the default on first use while leaving every separately configured add-on in the list. */
internal fun includeDefaultBuiltinCatalogSource(sources: List<CatalogSource>): List<CatalogSource> =
  normalizeCatalogSources(sources, builtinRemoved = false)

/** Normalize saved sources without recreating Cinemeta after the user has removed it. */
internal fun normalizeCatalogSources(
  sources: List<CatalogSource>,
  builtinRemoved: Boolean,
): List<CatalogSource> {
  val builtin = sources.firstOrNull(::isDefaultBuiltinCatalogSource)
  val normalizedBuiltin = when {
    builtinRemoved -> null
    builtin != null -> builtin.copy(name = DEFAULT_BUILTIN_CATALOG_SOURCE.name, manifestUrl = BUILTIN_CATALOG_MANIFEST_URL)
    else -> DEFAULT_BUILTIN_CATALOG_SOURCE
  }
  val configured = sources.asSequence()
    .filterNot { it.id.startsWith("builtin-", ignoreCase = true) }
    .filterNot { it.manifestUrl.trim().equals(BUILTIN_CATALOG_MANIFEST_URL, ignoreCase = true) }
    .filter { isHttpAddonEndpoint(it.manifestUrl) }
    .distinctBy { it.manifestUrl.trim().lowercase(Locale.ROOT) }
    .toList()
  return listOfNotNull(normalizedBuiltin) + configured
}

/** Keep invalid legacy placeholders out of every catalog, metadata, and stream request path. */
internal fun requestableCatalogSources(sources: List<CatalogSource>): List<CatalogSource> =
  sources.asSequence()
    .filter { it.isEnabled }
    .filter { !it.id.startsWith("builtin-", ignoreCase = true) || isDefaultBuiltinCatalogSource(it) }
    .filter { isHttpAddonEndpoint(it.manifestUrl) }
    .distinctBy { it.manifestUrl.trim().lowercase(Locale.ROOT) }
    .toList()

/** Each configured source gets an independent coroutine; failures do not cancel other sources. */
internal suspend fun <T> loadCatalogSourcesIndependently(
  sources: List<CatalogSource>,
  load: suspend (CatalogSource) -> T,
): List<Pair<CatalogSource, Result<T>>> = coroutineScope {
  sources.map { source ->
    async {
      source to try {
        Result.success(load(source))
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        Result.failure(failure)
      }
    }
  }.awaitAll()
}
