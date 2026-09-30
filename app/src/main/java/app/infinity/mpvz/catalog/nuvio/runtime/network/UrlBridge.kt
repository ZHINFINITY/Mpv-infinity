package app.infinity.mpvz.catalog.nuvio.runtime.network

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import app.infinity.mpvz.catalog.nuvio.runtime.host.HostModule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

internal class UrlBridge : HostModule {
    override fun register(runtime: QuickJs) {
        runtime.function("__parse_url") { args ->
            val urlString = args.getOrNull(0)?.toString() ?: ""
            parseUrl(urlString)
        }
    }

    private fun parseUrl(urlString: String): String {
        return try {
            val parsed = URI(urlString)
            val protocol = parsed.scheme?.let { "$it:" }.orEmpty()
            val hostName = parsed.host.orEmpty()
            val portValue = parsed.port.takeIf { it >= 0 }?.toString().orEmpty()
            val authority = if (portValue.isBlank()) hostName else "$hostName:$portValue"
            JsonObject(
                mapOf(
                    "protocol" to JsonPrimitive(protocol),
                    "host" to JsonPrimitive(authority),
                    "hostname" to JsonPrimitive(hostName),
                    "port" to JsonPrimitive(portValue),
                    "pathname" to JsonPrimitive(parsed.rawPath?.ifBlank { "/" } ?: "/"),
                    "search" to JsonPrimitive(parsed.rawQuery?.let { "?$it" } ?: ""),
                    "hash" to JsonPrimitive(parsed.rawFragment?.let { "#$it" } ?: ""),
                ),
            ).toString()
        } catch (_: Exception) {
            JsonObject(
                mapOf(
                    "protocol" to JsonPrimitive(""),
                    "host" to JsonPrimitive(""),
                    "hostname" to JsonPrimitive(""),
                    "port" to JsonPrimitive(""),
                    "pathname" to JsonPrimitive("/"),
                    "search" to JsonPrimitive(""),
                    "hash" to JsonPrimitive(""),
                ),
            ).toString()
        }
    }
}
