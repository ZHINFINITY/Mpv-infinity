package app.infinity.mpvz.domain.audiobook

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import app.infinity.mpvz.utils.media.openPersistedTreeDocument
import app.infinity.mpvz.utils.storage.FileTypeUtils
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale

internal data class AudiobookFolderEntry(
  val uri: String,
  val name: String,
)

internal data class AudiobookFolderTrack(
  val uri: String,
  val name: String,
  val mimeType: String?,
  val sizeBytes: Long,
)

internal data class AudiobookFolderListing(
  val folder: AudiobookFolderEntry,
  val parentUri: String?,
  val folders: List<AudiobookFolderEntry>,
  val tracks: List<AudiobookFolderTrack>,
)

internal data class AudiobookFolderTree(
  val rootUri: String,
  val listings: Map<String, AudiobookFolderListing>,
) {
  fun listing(uri: String): AudiobookFolderListing? = listings[uri]

  fun tracksUnder(folderUri: String): List<AudiobookFolderTrack> {
    val pending = ArrayDeque<String>().apply { addLast(folderUri) }
    val visited = mutableSetOf<String>()
    val tracks = mutableListOf<AudiobookFolderTrack>()
    while (pending.isNotEmpty()) {
      val currentUri = pending.removeFirst()
      if (!visited.add(currentUri)) continue
      val current = listing(currentUri) ?: continue
      tracks += current.tracks
      current.folders.asReversed().forEach { pending.addFirst(it.uri) }
    }
    return tracks
  }
}

/** Reads a persisted document tree without creating files, copying audio, or writing library rows. */
internal object AudiobookFolderScanner {
  fun scan(context: Context, treeUri: String): AudiobookFolderTree {
    val root = openPersistedTreeDocument(context, treeUri)
      ?: throw SecurityException("The selected audiobook folder is not readable")

    val visits = walkAudiobookTree(
      root = root,
      identity = { it.uri.toString() },
      children = { directory ->
        if (!directory.canRead()) emptyList()
        else runCatching { directory.listFiles().toList() }.getOrDefault(emptyList())
      },
      shouldVisitChild = { child -> child.isDirectory && !child.name.orEmpty().startsWith(".") },
    )

    val listings = visits.associate { visit ->
      val folder = visit.node.asFolderEntry()
      val visibleFolders = visit.children
        .filter { it.isDirectory && !it.name.orEmpty().startsWith(".") }
        .map { it.asFolderEntry() }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
      val tracks = visit.children
        .filter { !it.isDirectory && isSupportedAudio(it.name, it.type) }
        .map { document ->
          AudiobookFolderTrack(
            uri = document.uri.toString(),
            name = document.name?.takeIf(String::isNotBlank) ?: "Audio file",
            mimeType = document.type?.takeIf(String::isNotBlank),
            sizeBytes = document.length().coerceAtLeast(0L),
          )
        }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
      folder.uri to AudiobookFolderListing(
        folder = folder,
        parentUri = visit.parent?.uri?.toString(),
        folders = visibleFolders,
        tracks = tracks,
      )
    }
    return AudiobookFolderTree(root.uri.toString(), listings)
  }

  internal fun isSupportedAudio(name: String?, mimeType: String?): Boolean {
    if (mimeType?.startsWith("audio/", ignoreCase = true) == true) return true
    val extension = name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in FileTypeUtils.AUDIO_EXTENSIONS
  }

  private fun DocumentFile.asFolderEntry(): AudiobookFolderEntry = AudiobookFolderEntry(
    uri = uri.toString(),
    name = name?.takeIf(String::isNotBlank) ?: "Audiobooks",
  )
}

internal fun directAudiobookFolderIdentity(folderUri: String): String =
  digestIdentity("folder\u0000$folderUri")

internal fun directAudiobookSelectionIdentity(trackUris: List<String>): String =
  digestIdentity("selection\u0000${trackUris.distinct().sorted().joinToString("\u0000")}")

internal fun directAudiobookTrackIdentity(trackUri: String): String =
  digestIdentity("track\u0000$trackUri")

private fun digestIdentity(value: String): String = MessageDigest.getInstance("SHA-256")
  .digest(value.toByteArray(Charsets.UTF_8))
  .joinToString("") { "%02x".format(it) }
