package app.infinity.mpvz.catalog.nuvio.runtime.network

import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import app.infinity.mpvz.catalog.nuvio.runtime.host.HostModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal class FetchBridge : HostModule {
  private val json = Json { ignoreUnknownKeys = true }
  override fun register(runtime: QuickJs) {
    runtime.asyncFunction("__native_fetch") { args ->
      val url = args.getOrNull(0)?.toString().orEmpty()
      val method = args.getOrNull(1)?.toString()?.uppercase() ?: "GET"
      val headerText = args.getOrNull(2)?.toString() ?: "{}"
      val bodyKind = args.getOrNull(3)?.toString() ?: "none"
      val bodyValue = args.getOrNull(4)?.toString().orEmpty()
      val followRedirects = args.getOrNull(5) as? Boolean ?: true
      runCatching { request(url, method, headerText, bodyKind, bodyValue, followRedirects) }
        .getOrElse { failure(url, it.message ?: "Fetch failed") }
    }
  }

  private suspend fun request(url: String, method: String, headerText: String, bodyKind: String, bodyValue: String, followRedirects: Boolean): String = withContext(Dispatchers.IO) {
    val headers = runCatching { (json.parseToJsonElement(headerText) as? JsonObject).orEmpty().mapNotNull { (key, value) -> value.jsonPrimitive.contentOrNull?.let { key to it } }.toMap() }.getOrDefault(emptyMap())
    val payload = when (bodyKind) {
      "base64" -> Base64.decode(bodyValue, Base64.DEFAULT)
      "text" -> bodyValue.toByteArray(Charsets.UTF_8)
      else -> null
    }
    val client = sharedClient.newBuilder().followRedirects(followRedirects).followSslRedirects(followRedirects).build()
    val builder = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }
    if (headers.keys.none { it.equals("user-agent", true) }) builder.header("User-Agent", DEFAULT_USER_AGENT)
    val body = payload?.toRequestBody(null)
    builder.method(method, if (method in BODY_METHODS) body ?: ByteArray(0).toRequestBody(null) else null)
    client.newCall(builder.build()).execute().use { response ->
      val bytes = response.body.bytes()
      val text = bytes.toString(Charsets.UTF_8)
      val responseHeaders = response.headers.toMultimap().mapValues { (_, values) -> values.joinToString(", ").take(MAX_HEADER_CHARS) }
      JsonObject(mapOf(
        "ok" to JsonPrimitive(response.isSuccessful),
        "status" to JsonPrimitive(response.code),
        "statusText" to JsonPrimitive(response.message),
        "url" to JsonPrimitive(response.request.url.toString()),
        "body" to JsonPrimitive(text),
        "bodyBase64" to JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)),
        "headers" to JsonObject(responseHeaders.mapValues { JsonPrimitive(it.value) }),
      )).toString()
    }
  }

  private fun failure(url: String, message: String): String = JsonObject(mapOf(
    "ok" to JsonPrimitive(false), "status" to JsonPrimitive(0), "statusText" to JsonPrimitive(message),
    "url" to JsonPrimitive(url), "body" to JsonPrimitive(""), "bodyBase64" to JsonPrimitive(""),
    "headers" to JsonObject(emptyMap()),
  )).toString()

  private companion object {
    val sharedClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    val BODY_METHODS = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/123.0 Mobile Safari/537.36"
    const val MAX_HEADER_CHARS = 8192
  }
}
