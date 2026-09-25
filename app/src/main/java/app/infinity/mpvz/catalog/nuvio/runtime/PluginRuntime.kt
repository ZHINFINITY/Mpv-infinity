package app.infinity.mpvz.catalog.nuvio.runtime

import android.util.Log
import app.infinity.mpvz.catalog.redactAddonConfigurationFromLog
import app.infinity.mpvz.catalog.nuvio.PluginRuntimeResult
import app.infinity.mpvz.catalog.nuvio.PluginSettingField
import app.infinity.mpvz.catalog.nuvio.PluginSubtitleResult
import app.infinity.mpvz.catalog.nuvio.runtime.crypto.CryptoBridge
import app.infinity.mpvz.catalog.nuvio.runtime.dom.DomBridge
import app.infinity.mpvz.catalog.nuvio.runtime.host.HostApiRegistry
import app.infinity.mpvz.catalog.nuvio.runtime.host.HostFunctions
import app.infinity.mpvz.catalog.nuvio.runtime.js.JsBindings
import app.infinity.mpvz.catalog.nuvio.runtime.js.JsRuntime
import app.infinity.mpvz.catalog.nuvio.runtime.network.FetchBridge
import app.infinity.mpvz.catalog.nuvio.runtime.network.UrlBridge
import app.infinity.mpvz.catalog.nuvio.runtime.wasm.WasmBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val MAX_CONCURRENT_PLUGINS = 10
internal const val PLUGIN_TIMEOUT_MS = 60_000L

internal object PluginRuntime {
  private val json = Json { ignoreUnknownKeys = true }
  private val scraperSemaphore = Semaphore(MAX_CONCURRENT_PLUGINS)

  suspend fun executePlugin(code: String, tmdbId: String, mediaType: String, season: Int?, episode: Int?, scraperId: String, tmdbApiKey: String, scraperSettingsJson: String): List<PluginRuntimeResult> =
    scraperSemaphore.withPermit {
      withContext(pluginDispatcher) {
        withTimeout(PLUGIN_TIMEOUT_MS) { executeInternal(code, tmdbId, mediaType, season, episode, scraperId, tmdbApiKey, scraperSettingsJson) }
      }
    }

  suspend fun getSettingsLayout(code: String, scraperId: String, tmdbApiKey: String, scraperSettingsJson: String): List<PluginSettingField> = withContext(pluginDispatcher) {
    withTimeout(PLUGIN_TIMEOUT_MS) {
      val result = CompletableDeferred<String>()
      val host = HostApiRegistry().apply {
        addModule(HostFunctions(scraperId = scraperId, scraperSettingsJson = scraperSettingsJson, tmdbApiKey = tmdbApiKey) { result.complete(it) })
        addModule(FetchBridge())
        addModule(UrlBridge())
        addModule(CryptoBridge())
      }
      JsRuntime().use {
        host.registerAll(this)
        evaluate<Any?>(JsBindings.staticPolyfillCode)
        evaluate<Any?>("var module = { exports: {} }; var exports = module.exports; (function(){ $code })();")
        evaluate<Any?>("(async function(){ try { var f=module.exports.onSettings||globalThis.onSettings; __capture_result(JSON.stringify(typeof f==='function' ? (await f()) || [] : [])); } catch(e) { console.error('onSettings error', e); __capture_result('[]'); } })();")
        runCatching { json.decodeFromString<List<PluginSettingField>>(result.await()) }.getOrDefault(emptyList())
      }
    }
  }

  private suspend fun executeInternal(code: String, tmdbId: String, mediaType: String, season: Int?, episode: Int?, scraperId: String, tmdbApiKey: String, scraperSettingsJson: String): List<PluginRuntimeResult> {
    val result = CompletableDeferred<String>()
    val args = JsonObject(mapOf(
      "tmdbId" to JsonPrimitive(tmdbId),
      "mediaType" to JsonPrimitive(if (mediaType == "movie") "movie" else "tv"),
      "season" to (season?.let(::JsonPrimitive) ?: JsonNull),
      "episode" to (episode?.let(::JsonPrimitive) ?: JsonNull),
    )).toString()
    val dom = DomBridge()
    return try {
      val host = HostApiRegistry().apply {
        addModule(HostFunctions(scraperId = scraperId, scraperSettingsJson = scraperSettingsJson, tmdbApiKey = tmdbApiKey, callArgsJson = args) { result.complete(it) })
        addModule(FetchBridge())
        addModule(UrlBridge())
        addModule(CryptoBridge())
        addModule(WasmBridge())
        addModule(dom)
      }
      JsRuntime().use {
        host.registerAll(this)
        evaluate<Any?>(JsBindings.staticPolyfillCode)
        evaluate<Any?>("var module = { exports: {} }; var exports = module.exports; (function(){ $code })();")
        evaluate<Any?>(JsBindings.staticCallCode)
        parseResults(result.await())
      }
    } catch (error: Throwable) {
      val safeProviderLabel = "provider-${scraperId.hashCode().toUInt().toString(16)}"
      Log.e("NuvioPlugin", "Provider execution failed id=$safeProviderLabel: ${redactAddonConfigurationFromLog(error.message.orEmpty())}")
      throw error
    } finally {
      dom.clear()
    }
  }

  private fun parseResults(raw: String): List<PluginRuntimeResult> = runCatching {
    val values = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
    values.mapNotNull { entry ->
      val item = entry as? JsonObject ?: return@mapNotNull null
      val rawUrl = item["url"]
      val url = when (rawUrl) {
        is JsonPrimitive -> rawUrl.contentOrNull
        is JsonObject -> rawUrl["url"]?.jsonPrimitive?.contentOrNull
        else -> null
      }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val headers = item["headers"]?.let(::stringMap)
      val subtitles = (item["subtitles"] as? JsonArray)?.mapNotNull subtitle@{ element ->
        val sub = element as? JsonObject ?: return@subtitle null
        val subUrl = sub["url"]?.jsonPrimitive?.contentOrNull ?: return@subtitle null
        PluginSubtitleResult(subUrl, sub.string("language") ?: "Unknown", sub.string("name"), sub["headers"]?.let(::stringMap))
      }.orEmpty()
      PluginRuntimeResult(
        title = item.string("title") ?: item.string("name") ?: "Unknown source",
        name = item.string("name"), url = url, quality = item.string("quality"), size = item.string("size"),
        language = item.string("language"), provider = item.string("provider"), type = item.string("type"),
        seeders = item["seeders"]?.jsonPrimitive?.intOrNull, peers = item["peers"]?.jsonPrimitive?.intOrNull,
        infoHash = item.string("infoHash"), headers = headers, subtitles = subtitles,
      )
    }
  }.onFailure { Log.w("NuvioPlugin", "Could not parse scraper response: ${redactAddonConfigurationFromLog(it.message.orEmpty())}") }.getOrDefault(emptyList())

  private fun stringMap(element: JsonElement): Map<String, String> =
    (element as? JsonObject)?.mapNotNull { (key, value) -> value.jsonPrimitive.contentOrNull?.let { key to it } }?.toMap().orEmpty()
  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && !it.contains("[object") }
}
