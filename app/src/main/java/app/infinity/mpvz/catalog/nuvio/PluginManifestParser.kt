package app.infinity.mpvz.catalog.nuvio

import kotlinx.serialization.json.Json

internal object PluginManifestParser {
  private val json = Json { ignoreUnknownKeys = true }
  fun parse(payload: String): PluginManifest {
    val manifest = json.decodeFromString<PluginManifest>(payload)
    require(manifest.name.isNotBlank()) { "Repository manifest has no name." }
    require(manifest.version.isNotBlank()) { "Repository manifest has no version." }
    require(manifest.scrapers.isNotEmpty()) { "Repository manifest contains no scrapers/providers." }
    require(manifest.scrapers.all { it.id.isNotBlank() && it.filename.isNotBlank() && it.name.isNotBlank() }) { "One or more providers are missing required manifest fields." }
    return manifest
  }
}
