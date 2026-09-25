package app.infinity.mpvz.catalog.nuvio.runtime.host

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function

internal class HostFunctions(
  private val scraperId: String,
  private val scraperSettingsJson: String = "{}",
  private val tmdbApiKey: String = "",
  private val callArgsJson: String = "{}",
  private val onResult: (String) -> Unit,
) : HostModule {
  override fun register(runtime: QuickJs) {
    runtime.define("console") {
      function("log") { args -> Log.d(TAG, "Plugin:$scraperId ${args.joinToString(" ") { it?.toString().orEmpty() }.take(1200)}"); null }
      function("info") { args -> Log.i(TAG, "Plugin:$scraperId ${args.joinToString(" ") { it?.toString().orEmpty() }.take(1200)}"); null }
      function("warn") { args -> Log.w(TAG, "Plugin:$scraperId ${args.joinToString(" ") { it?.toString().orEmpty() }.take(1200)}"); null }
      function("error") { args -> Log.e(TAG, "Plugin:$scraperId ${args.joinToString(" ") { it?.toString().orEmpty() }.take(1200)}"); null }
      function("debug") { args -> Log.d(TAG, "Plugin:$scraperId ${args.joinToString(" ") { it?.toString().orEmpty() }.take(1200)}"); null }
    }
    runtime.function("__get_scraper_id") { scraperId }
    runtime.function("__get_scraper_settings") { scraperSettingsJson }
    runtime.function("__get_tmdb_api_key") { tmdbApiKey }
    runtime.function("__get_call_args") { callArgsJson }
    runtime.function("__capture_result") { args -> onResult(args.getOrNull(0)?.toString() ?: "[]"); null }
  }

  private companion object { const val TAG = "NuvioPlugin" }
}
