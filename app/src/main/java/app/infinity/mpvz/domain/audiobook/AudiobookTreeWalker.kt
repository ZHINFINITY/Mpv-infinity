package app.infinity.mpvz.domain.audiobook

import java.util.ArrayDeque

internal data class AudiobookTreeVisit<T>(
  val node: T,
  val parent: T?,
  val children: List<T>,
)

internal fun <T> walkAudiobookTree(
  root: T,
  identity: (T) -> String,
  children: (T) -> Iterable<T>,
  shouldVisitChild: (T) -> Boolean = { true },
): List<AudiobookTreeVisit<T>> {
  val pending = ArrayDeque<Pair<T, T?>>().apply { addLast(root to null) }
  val visited = mutableSetOf<String>()
  val result = mutableListOf<AudiobookTreeVisit<T>>()

  while (pending.isNotEmpty()) {
    val (node, parent) = pending.removeFirst()
    if (!visited.add(identity(node))) continue
    val directChildren = children(node).toList()
    result += AudiobookTreeVisit(node, parent, directChildren)
    directChildren.filter(shouldVisitChild).forEach { child -> pending.addLast(child to node) }
  }

  return result
}
