package app.infinity.mpvz.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamResultAggregatorTest {
  @Test
  fun incrementalBatchesAppendInArrivalOrderAndDeduplicateUrls() {
    val first = StreamOption(url = "https://stream.invalid/one", title = "First")
    val duplicate = StreamOption(url = "https://stream.invalid/one", title = "Duplicate")
    val late = StreamOption(url = "https://stream.invalid/two", title = "Late")
    val invalid = StreamOption(url = "magnet:?fixture=1", title = "Not playable")

    val initial = mergeStreamOptions(emptyList(), listOf(first, invalid))
    val updated = mergeStreamOptions(initial, listOf(duplicate, late))

    assertEquals(listOf(first, late), updated)
  }
}
