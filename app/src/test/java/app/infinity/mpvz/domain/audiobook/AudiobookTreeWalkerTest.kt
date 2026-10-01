package app.infinity.mpvz.domain.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookTreeWalkerTest {
  private data class Node(
    val path: String,
    val directory: Boolean = true,
    val children: MutableList<Node> = mutableListOf(),
  )

  @Test
  fun traversesNestedFoldersAndKeepsParentRelationships() {
    val root = Node("root")
    val book = Node("root/book")
    val disc = Node("root/book/disc")
    val nested = Node("root/book/disc/nested")
    val hidden = Node("root/.hidden")
    val track = Node("root/book/disc/nested/chapter.m4b", directory = false)
    root.children += listOf(book, hidden)
    book.children += disc
    disc.children += nested
    nested.children += track

    val visits = walkAudiobookTree(
      root = root,
      identity = Node::path,
      children = Node::children,
      shouldVisitChild = { it.directory && !it.path.substringAfterLast('/').startsWith(".") },
    )

    assertEquals(listOf("root", "root/book", "root/book/disc", "root/book/disc/nested"), visits.map { it.node.path })
    assertEquals("root/book", visits[2].parent?.path)
    assertEquals("root/book/disc/nested/chapter.m4b", visits.last().children.single().path)
  }

  @Test
  fun visitedIdentityPreventsCycles() {
    val root = Node("root")
    val child = Node("root/child")
    root.children += child
    child.children += root

    val visits = walkAudiobookTree(root, Node::path, Node::children)

    assertEquals(listOf("root", "root/child"), visits.map { it.node.path })
  }
}
