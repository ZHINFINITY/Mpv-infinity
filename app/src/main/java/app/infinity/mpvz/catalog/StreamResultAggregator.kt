package app.infinity.mpvz.catalog

/** Appends a provider batch without losing earlier links or duplicating a URL. */
internal fun mergeStreamOptions(
  existing: List<StreamOption>,
  incoming: List<StreamOption>,
): List<StreamOption> =
  (existing + incoming)
    .filter { isDirectHttpStreamUrl(it.url) }
    .distinctBy { it.url }
