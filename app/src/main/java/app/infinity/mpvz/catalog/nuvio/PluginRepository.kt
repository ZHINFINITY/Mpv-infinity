package app.infinity.mpvz.catalog.nuvio

import android.content.Context
import android.util.Log
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.catalog.StreamOption
import app.infinity.mpvz.catalog.redactAddonConfigurationFromLog
import app.infinity.mpvz.repository.wyzie.WyzieTmdbResponse
import app.infinity.mpvz.catalog.nuvio.runtime.PluginRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Nuvio-compatible JavaScript scraper repositories. This is intentionally not Stremio's HTTP API. */
class PluginRepository(context: Context) {
  private val app = context.applicationContext
  private val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
    app,
    PREFS,
    androidx.security.crypto.MasterKey.Builder(app).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
  )
  private val codeDir = File(app.filesDir, "nuvio-plugin-code").apply { mkdirs() }
  private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val _uiState = MutableStateFlow(loadState())
  val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

  suspend fun addRepository(rawUrl: String): Result<PluginRepositoryItem> = withContext(Dispatchers.IO) {
    runCatching {
      val manifestUrl = normalizeManifestUrl(rawUrl)
      require(_uiState.value.repositories.none { it.manifestUrl.equals(manifestUrl, true) }) { "This provider repository is already installed." }
      val (repo, scrapers) = fetchRepositoryData(manifestUrl, emptyMap())
      require(scrapers.isNotEmpty()) { "No Android-compatible provider code could be downloaded from this repository." }
      _uiState.update { it.copy(repositories = it.repositories + repo, scrapers = it.scrapers + scrapers) }
      persist()
      repo
    }
  }

  suspend fun importIfNuvioRepository(url: String): Boolean {
    if (_uiState.value.repositories.any { it.manifestUrl.equals(url, true) }) return true
    return addRepository(url).isSuccess
  }

  suspend fun refreshRepository(manifestUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      val repo = _uiState.value.repositories.firstOrNull { it.manifestUrl == manifestUrl } ?: error("Repository not found")
      _uiState.update { state -> state.copy(repositories = state.repositories.map { if (it.manifestUrl == manifestUrl) it.copy(isRefreshing = true, errorMessage = null) else it }) }
      val previous = _uiState.value.scrapers.associateBy { it.id }
      val (updated, scrapers) = fetchRepositoryData(manifestUrl, previous)
      _uiState.update { state -> state.copy(repositories = state.repositories.map { if (it.manifestUrl == manifestUrl) updated else it }, scrapers = state.scrapers.filterNot { it.repositoryUrl == manifestUrl } + scrapers) }
      persist()
      Unit
    }.onFailure { error ->
      _uiState.update { state -> state.copy(repositories = state.repositories.map { if (it.manifestUrl == manifestUrl) it.copy(isRefreshing = false, errorMessage = error.message ?: "Refresh failed") else it }) }
      persist()
    }
  }

  suspend fun refreshAll() = coroutineScope {
    _uiState.value.repositories.map { repo -> async(Dispatchers.IO) { refreshRepository(repo.manifestUrl) } }.awaitAll()
  }

  fun removeRepository(manifestUrl: String) {
    refreshFromDisk()
    val oldCodes = _uiState.value.scrapers.filter { it.repositoryUrl == manifestUrl }
    _uiState.update { it.copy(repositories = it.repositories.filterNot { r -> r.manifestUrl == manifestUrl }, scrapers = it.scrapers.filterNot { s -> s.repositoryUrl == manifestUrl }) }
    oldCodes.forEach { codeFile(it.id).delete() }
    persist()
  }

  fun toggleScraper(scraperId: String, enabled: Boolean) {
    refreshFromDisk()
    _uiState.update { state -> state.copy(scrapers = state.scrapers.map { if (it.id == scraperId) it.copy(enabled = enabled && it.manifestEnabled) else it }) }
    persist()
  }

  fun refreshFromDisk() { _uiState.value = loadState() }
  fun tmdbApiKey(): String = prefs.getString(KEY_TMDB_API_KEY, "").orEmpty()
  suspend fun setTmdbApiKey(value: String): Boolean = withContext(Dispatchers.IO) {
    val normalized = value.trim()
    val committed = prefs.edit().putString(KEY_TMDB_API_KEY, normalized).commit()
    committed && tmdbApiKey() == normalized
  }
  fun scraperSettings(scraperId: String): Map<String, String> = runCatching {
    json.decodeFromString<Map<String, String>>(prefs.getString("settings_${stableHash(scraperId)}", "{}") ?: "{}")
  }.getOrDefault(emptyMap())
  fun saveScraperSettings(scraperId: String, values: Map<String, String>) {
    prefs.edit().putString("settings_${stableHash(scraperId)}", json.encodeToString(values)).apply()
  }
  suspend fun settingsLayout(scraperId: String): List<PluginSettingField> {
    refreshFromDisk()
    val scraper = _uiState.value.scrapers.firstOrNull { it.id == scraperId } ?: error("Provider not found")
    require(scraper.hasSettings) { "This provider does not declare configurable settings." }
    return PluginRuntime.getSettingsLayout(scraper.code, scraper.id, tmdbApiKey(), json.encodeToString(scraperSettings(scraper.id)))
  }

  suspend fun resolve(item: MediaItem, season: Int? = null, episode: Int? = null): List<StreamOption> {
    refreshFromDisk()
    val type = if (item.type == MediaType.MOVIE) "movie" else "tv"
    val tmdbId = resolveTmdbId(item, type) ?: throw IllegalStateException("Could not map '${item.title}' to a TMDB ID. Check the title/year metadata and try again.")
    val matchingProviders = _uiState.value.scrapers.filter { it.manifestEnabled && it.supportsType(type) }
    val enabledProviders = matchingProviders.filter { it.enabled }
    Log.i(TAG, "resolve title=${item.title} tmdb=$tmdbId type=$type repositories=${_uiState.value.repositories.size} providers=${matchingProviders.size} enabled=${enabledProviders.size}")
    // A previous build could persist every provider switch as disabled while the
    // manifest itself declared the providers enabled. Nuvio treats a newly installed
    // manifest as active, so retain that behavior when no provider is enabled.
    val providers = enabledProviders.ifEmpty { matchingProviders }
    if (providers.isEmpty()) throw IllegalStateException("No enabled Nuvio providers support $type. Open Settings → Network → Media Servers → Nuvio Providers.")
    if (enabledProviders.isEmpty()) Log.w(TAG, "No provider switches enabled; using ${providers.size} manifest-enabled $type providers")
    return withTimeout(PROVIDER_GROUP_TIMEOUT_MS) {
      coroutineScope {
        providers.map { provider -> async(Dispatchers.IO) {
          runCatching { PluginRuntime.executePlugin(provider.code, tmdbId.toString(), type, season, episode, provider.id, tmdbApiKey(), json.encodeToString(scraperSettings(provider.id))) }
            .onFailure { Log.w(TAG, "Provider failed name=${provider.name} type=$type: ${redactAddonConfigurationFromLog(it.message.orEmpty())}") }
            .onSuccess { Log.i(TAG, "Provider returned name=${provider.name} type=$type raw=${it.size}") }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { stream ->
              (stream.url.startsWith("http://", true) || stream.url.startsWith("https://", true)) &&
                stream.infoHash.isNullOrBlank() &&
                !stream.type.orEmpty().contains("torrent", true)
            }
            .map { stream ->
              val providerLabel = stream.provider?.takeIf(String::isNotBlank) ?: provider.name
              StreamOption(
                url = stream.url,
                title = stream.title.ifBlank { stream.name ?: "${provider.name} stream" },
                headers = stream.headers.orEmpty(),
                filename = stream.name,
                qualityRank = stream.quality?.let { Regex("(\\d{3,4})\\s*p?", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() } ?: 0,
                seeders = stream.seeders ?: 0,
                size = stream.size,
                source = "$providerLabel · ${provider.name}",
                isPlayable = true,
                isExternal = false,
                season = season,
                episode = episode,
              )
            }.toList()
        } }.awaitAll().flatten().distinctBy { it.url }.sortedWith(compareByDescending<StreamOption> { it.qualityRank }.thenBy { it.source.orEmpty() })
      }
    }
  }

  private suspend fun resolveTmdbId(item: MediaItem, type: String): Int? = withContext(Dispatchers.IO) {
    item.tmdbId?.takeIf { it > 0 }?.let { return@withContext it }
    val providerId = item.providerId.orEmpty().trim()
    val normalizedProviderId = providerId
      .replace(Regex("^(?:tmdb[:/]|movie:|series:)", RegexOption.IGNORE_CASE), "")
      .substringBefore(':')
      .substringBefore('/')
      .trim()
    normalizedProviderId.toIntOrNull()?.takeIf { it > 0 }?.let { return@withContext it }

    val imdbId = (item.imdbId ?: providerId.takeIf { it.startsWith("tt", ignoreCase = true) })
      ?.trim()
      ?.substringBefore(':')
      ?.takeIf { it.startsWith("tt", ignoreCase = true) }
    val apiKey = tmdbApiKey()
    if (imdbId != null && apiKey.isNotBlank()) {
      runCatching { findTmdbIdByImdb(imdbId, type, apiKey) }.getOrNull()?.let { return@withContext it }
    }

    val query = buildString { append(item.title); item.releaseYear?.take(4)?.let { append(" ").append(it) } }
    val url = "https://sub.wyzie.io/api/tmdb/search?q=${URLEncoder.encode(query, "UTF-8")}"
    val response = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()).execute().use { response ->
      if (!response.isSuccessful) error("Metadata ID lookup failed (${response.code})")
      json.decodeFromString<WyzieTmdbResponse>(response.body.string()).results
    }
    val desiredYear = item.releaseYear?.take(4)
    val typeMatches = response.filter { it.mediaType.equals(if (type == "movie") "movie" else "tv", true) }
    val choices = typeMatches.ifEmpty { response }
    choices.maxWithOrNull(compareBy({ normalizedTitle(it.title) == normalizedTitle(item.title) }, { desiredYear != null && it.releaseYear?.startsWith(desiredYear) == true }, { commonPrefixScore(normalizedTitle(it.title), normalizedTitle(item.title)) }))?.id
  }

  private fun findTmdbIdByImdb(imdbId: String, type: String, apiKey: String): Int? {
    val url = "https://api.themoviedb.org/3/find/${URLEncoder.encode(imdbId, "UTF-8")}?api_key=${URLEncoder.encode(apiKey, "UTF-8")}&external_source=imdb_id"
    val payload = client.newCall(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", USER_AGENT).get().build()).execute().use { response ->
      if (!response.isSuccessful) return null
      json.parseToJsonElement(response.body.string()).jsonObject
    }
    val resultsKey = if (type == "movie") "movie_results" else "tv_results"
    return payload[resultsKey]?.jsonArray.orEmpty().firstNotNullOfOrNull { entry ->
      entry.jsonObject["id"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
    }
  }

  private suspend fun fetchRepositoryData(url: String, previous: Map<String, PluginScraper>): Pair<PluginRepositoryItem, List<PluginScraper>> = coroutineScope {
    val payload = httpGetText(url)
    val manifest = PluginManifestParser.parse(payload)
    val semaphore = Semaphore(6)
    val scrapers = manifest.scrapers
      .filter { info ->
        val platform = "android"
        val supported = info.supportedPlatforms?.map { it.lowercase() }.orEmpty()
        val disabled = info.disabledPlatforms?.map { it.lowercase() }.orEmpty()
        (supported.isEmpty() || platform in supported) && platform !in disabled
      }
      .map { info -> async(Dispatchers.IO) { semaphore.withPermit { runCatching {
        val codeUrl = resolvePluginCodeUrl(url, info.filename)
        val code = httpGetText(codeUrl)
        require(code.isNotBlank()) { "Provider code is empty: ${info.name}" }
        val id = "${url.lowercase()}:${info.id}"
        codeFile(id).writeText(code)
        val old = previous[id]
        PluginScraper(
          id = id, repositoryUrl = url, name = info.name, description = info.description.orEmpty(), version = info.version,
          filename = info.filename, supportedTypes = info.supportedTypes, enabled = old?.enabled ?: info.enabled,
          manifestEnabled = info.enabled, hasSettings = info.hasSettings, logo = info.logo,
          contentLanguage = info.contentLanguage.orEmpty(), formats = info.formats ?: info.supportedFormats, code = code,
        )
      }.onFailure { Log.w(TAG, "Could not load provider ${info.name}: ${redactAddonConfigurationFromLog(it.message.orEmpty())}") }.getOrNull() } } }.awaitAll().filterNotNull()
    require(scrapers.isNotEmpty()) { "Manifest loaded, but none of its provider files could be downloaded. Check repository paths/permissions." }
    val repository = PluginRepositoryItem(url, manifest.name, manifest.description, manifest.version, scrapers.size, System.currentTimeMillis())
    repository to scrapers
  }

  private fun httpGetText(url: String): String = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json, text/plain, */*").get().build()).execute().use { response ->
    if (!response.isSuccessful) error("GET ${safeUrl(url)} returned HTTP ${response.code}")
    response.body.string()
  }

  private fun normalizeManifestUrl(raw: String): String {
    val input = raw.trim().removeSuffix("/")
    require(input.isNotBlank()) { "Enter a Nuvio provider repository URL." }
    val scheme = if (input.startsWith("http://", true) || input.startsWith("https://", true)) input else "https://$input"
    val clean = scheme.substringBefore('#')
    val path = clean.substringBefore('?').trimEnd('/')
    val query = clean.substringAfter('?', "")
    val manifestUrl = if (path.endsWith("/manifest.json", true)) path else "$path/manifest.json"
    val uri = java.net.URI(manifestUrl)
    require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) { "Enter a valid HTTP(S) repository URL." }
    return if (query.isBlank()) manifestUrl else "$manifestUrl?$query"
  }

  private fun loadState(): PluginsUiState {
    val stored = runCatching { json.decodeFromString<StoredState>(prefs.getString(KEY_STATE, "") ?: "") }.getOrNull() ?: return PluginsUiState()
    val repos = stored.repositories.map { PluginRepositoryItem(it.manifestUrl, it.name, it.description, it.version, it.scraperCount, it.lastUpdated) }
    val scrapers = stored.scrapers.mapNotNull { entry ->
      val file = codeFile(entry.id)
      if (!file.exists()) return@mapNotNull null
      PluginScraper(entry.id, entry.repositoryUrl, entry.name, entry.description, entry.version, entry.filename, entry.supportedTypes, entry.enabled, entry.manifestEnabled, entry.hasSettings, entry.logo, entry.contentLanguage, entry.formats, runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null)
    }
    return PluginsUiState(repositories = repos, scrapers = scrapers)
  }

  private fun persist() {
    val state = _uiState.value
    val stored = StoredState(
      repositories = state.repositories.map { StoredRepo(it.manifestUrl, it.name, it.description, it.version, it.scraperCount, it.lastUpdated) },
      scrapers = state.scrapers.map { StoredScraper(it.id, it.repositoryUrl, it.name, it.description, it.version, it.filename, it.supportedTypes, it.enabled, it.manifestEnabled, it.hasSettings, it.logo, it.contentLanguage, it.formats) },
    )
    prefs.edit().putString(KEY_STATE, json.encodeToString(stored)).commit()
  }

  private fun codeFile(id: String): File {
    return File(codeDir, "${stableHash(id)}.js")
  }
  private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

  private fun normalizedTitle(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
  private fun commonPrefixScore(a: String, b: String): Int = a.zip(b).takeWhile { it.first == it.second }.size
  private fun safeUrl(value: String): String = runCatching { val u = java.net.URI(value); "${u.scheme}://${u.host}" }.getOrDefault("repository")

  internal fun resolvePluginCodeUrl(manifestUrl: String, filename: String): String {
    val manifest = java.net.URI(manifestUrl)
    val candidate = java.net.URI(filename)
    if (candidate.isAbsolute) return candidate.toString()
    // Match NuvioMobile: filenames are repository-root relative, even when they begin with '/'.
    val base = manifestUrl.substringBefore('?').substringBefore('#').removeSuffix("/manifest.json").trimEnd('/')
    val relativePath = filename.trimStart('/')
    val resolved = java.net.URI("$base/$relativePath")
    val manifestQuery = manifest.rawQuery.orEmpty()
    if (manifestQuery.isBlank() || resolved.rawAuthority != manifest.rawAuthority) return resolved.toString()
    val combinedQuery = listOfNotNull(
      resolved.rawQuery?.takeIf { it.isNotBlank() },
      manifestQuery.takeIf { resolved.rawQuery?.contains(it) != true },
    ).joinToString("&")
    if (combinedQuery.isBlank()) return resolved.toString()
    return buildString {
      append(resolved.toString().substringBefore('?').substringBefore('#'))
      append('?').append(combinedQuery)
      resolved.rawFragment?.let { append('#').append(it) }
    }
  }

  @Serializable private data class StoredState(val repositories: List<StoredRepo> = emptyList(), val scrapers: List<StoredScraper> = emptyList())
  @Serializable private data class StoredRepo(val manifestUrl: String, val name: String, val description: String? = null, val version: String? = null, val scraperCount: Int = 0, val lastUpdated: Long = 0)
  @Serializable private data class StoredScraper(val id: String, val repositoryUrl: String, val name: String, val description: String, val version: String, val filename: String, val supportedTypes: List<String>, val enabled: Boolean, val manifestEnabled: Boolean, val hasSettings: Boolean = false, val logo: String? = null, val contentLanguage: List<String> = emptyList(), val formats: List<String>? = null)

  private companion object {
    const val PREFS = "nuvio_plugin_repositories"
    const val KEY_STATE = "state_v1"
    const val KEY_TMDB_API_KEY = "tmdb_api_key"
    const val TAG = "NuvioPlugin"
    const val USER_AGENT = "Mpv-infinity/1.0 (Android; Nuvio provider runtime)"
    const val PROVIDER_GROUP_TIMEOUT_MS = 90_000L
  }
}
