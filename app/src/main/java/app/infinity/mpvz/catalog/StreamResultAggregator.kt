package app.infinity.mpvz.catalog

/** Appends a provider batch without losing earlier links or duplicating a URL. */
internal fun mergeStreamOptions(
  existing: List<StreamOption>,
  incoming: List<StreamOption>,
): List<StreamOption> =
  (existing + incoming)
    .filter { it.url.startsWith("http://", ignoreCase = true) || it.url.startsWith("https://", ignoreCase = true) }
    .distinctBy { it.url }
