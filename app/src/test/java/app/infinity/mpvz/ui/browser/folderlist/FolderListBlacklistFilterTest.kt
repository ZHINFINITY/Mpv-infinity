/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.folderlist

import app.infinity.mpvz.domain.media.model.VideoFolder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderListBlacklistFilterTest {
  @Test
  fun emitsExactFolderFilteringAndUpdatesWhenBlacklistChanges() = runBlocking {
    val parent = folder(PARENT)
    val child = folder(CHILD)
    val sibling = folder(SIBLING)
    val allFolders = MutableStateFlow(listOf(parent, child, sibling))
    val blacklist = MutableStateFlow(emptySet<String>())
    val emittedPaths = Channel<List<String>>(Channel.UNLIMITED)

    val job =
      launch {
        FolderListBlacklistFilter
          .observe(allFolders, blacklist, rootPath = null)
          .collect { folders -> emittedPaths.send(folders.map { it.path }) }
      }

    try {
      assertEquals(
        listOf(PARENT, CHILD, SIBLING),
        withTimeout(5_000L) { emittedPaths.receive() },
      )

      blacklist.value = setOf(PARENT)
      assertEquals(
        listOf(CHILD, SIBLING),
        withTimeout(5_000L) { emittedPaths.receive() },
      )

      blacklist.value = setOf(PARENT, CHILD)
      assertEquals(
        listOf(SIBLING),
        withTimeout(5_000L) { emittedPaths.receive() },
      )
    } finally {
      job.cancelAndJoin()
      emittedPaths.close()
    }
  }

  private fun folder(path: String) =
    VideoFolder(
      bucketId = path,
      name = path.substringAfterLast('/'),
      path = path,
      videoCount = 1,
    )

  private companion object {
    const val PARENT = "/storage/emulated/0/Movies"
    const val CHILD = "$PARENT/Keep"
    const val SIBLING = "$PARENT/Other"
  }
}
