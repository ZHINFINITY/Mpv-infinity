package app.infinity.mpvz.domain.audiobook

import android.content.Context
import android.media.MediaMetadataRetriever
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
  val durationMs: Long = 0L,
  val title: String? = null,
  val artist: String? = null,
  val album: String? = null,
)

internal data class AudiobookFolderListing(
  val folder: AudiobookFolderEntry,
  val parentUri: String?,
  val folders: List<AudiobookFolderEntry>,
  val tracks: List<AudiobookFolderTrack>,
  val hasBookMetadata: Boolean = false,
  val coverUri: String? = null,
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

  /** Collects one logical book's tracks while stopping at separately identified nested books. */
  fun tracksForBook(folderUri: String): List<AudiobookFolderTrack> {
    val bookUris = bookListings().mapTo(mutableSetOf()) { it.folder.uri }
    val pending = ArrayDeque<String>().apply { addLast(folderUri) }
    val visited = mutableSetOf<String>()
    val tracks = mutableListOf<AudiobookFolderTrack>()
    while (pending.isNotEmpty()) {
      val currentUri = pending.removeFirst()
      if (!visited.add(currentUri)) continue
      if (currentUri != folderUri && currentUri in bookUris) continue
      val current = listing(currentUri) ?: continue
      tracks += current.tracks
      current.folders.asReversed().forEach { pending.addFirst(it.uri) }
    }
    return tracks
  }

  /** Finds logical books using the same metadata-folder boundaries as the importer, but keeps them virtual. */
  fun bookListings(): List<AudiobookFolderListing> {
    val directories = listings.values.toList()
    fun hasMetadataAncestor(directory: AudiobookFolderListing): Boolean {
      var parent = directory.parentUri?.let(listings::get)
      while (parent != null) {
        if (parent.hasBookMetadata) return true
        parent = parent.parentUri?.let(listings::get)
      }
      return false
    }

    val directAudioFolderUris = directories.filter { it.tracks.isNotEmpty() }.mapTo(mutableSetOf()) { it.folder.uri }
    fun hasDirectAudioAncestor(directory: AudiobookFolderListing): Boolean {
      var parent = directory.parentUri?.let(listings::get)
      while (parent != null) {
        if (parent.folder.uri in directAudioFolderUris && !parent.hasBookMetadata) return true
        parent = parent.parentUri?.let(listings::get)
      }
      return false
    }

    val candidates = directories.filter { directory ->
      directory.hasBookMetadata || directory.tracks.isNotEmpty() &&
        !hasMetadataAncestor(directory) && !hasDirectAudioAncestor(directory)
    }
    val candidateUris = candidates.mapTo(mutableSetOf()) { it.folder.uri }
    fun containsAudioOutsideNestedBooks(root: AudiobookFolderListing): Boolean {
      val pending = ArrayDeque<String>().apply { add(root.folder.uri) }
      val visited = mutableSetOf<String>()
      while (pending.isNotEmpty()) {
        val currentUri = pending.removeFirst()
        if (!visited.add(currentUri)) continue
        val current = listing(currentUri) ?: continue
        if (current.tracks.isNotEmpty()) return true
        current.folders.forEach { child ->
          if (child.uri !in candidateUris) pending.addLast(child.uri)
        }
      }
      return false
    }

    return candidates.filter(::containsAudioOutsideNestedBooks).ifEmpty {
      listing(rootUri)?.takeIf { tracksUnder(rootUri).isNotEmpty() }?.let(::listOf).orEmpty()
    }
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
          val metadata = readTrackMetadata(context, document)
          AudiobookFolderTrack(
            uri = document.uri.toString(),
            name = document.name?.takeIf(String::isNotBlank) ?: "Audio file",
            mimeType = document.type?.takeIf(String::isNotBlank),
            sizeBytes = document.length().coerceAtLeast(0L),
            durationMs = metadata.durationMs,
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
          )
        }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
      val bookMetadata = visit.children.any { child ->
        child.isFile && (child.name?.equals("metadata.json", true) == true || child.name?.endsWith(".opf", true) == true)
      }
      val coverUri = visit.children.asSequence()
        .filter { it.isFile }
        .firstOrNull { child -> child.name?.let(::isCoverArtworkName) == true }
        ?.uri?.toString()
      folder.uri to AudiobookFolderListing(
        folder = folder,
        parentUri = visit.parent?.uri?.toString(),
        folders = visibleFolders,
        tracks = tracks,
        hasBookMetadata = bookMetadata,
        coverUri = coverUri,
      )
    }
    return AudiobookFolderTree(root.uri.toString(), listings)
  }

  internal fun isSupportedAudio(name: String?, mimeType: String?): Boolean {
    if (mimeType?.startsWith("audio/", ignoreCase = true) == true) return true
    val extension = name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
    return extension in FileTypeUtils.AUDIO_EXTENSIONS
  }

  private data class TrackMetadata(
    val durationMs: Long = 0L,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
  )

  private fun readTrackMetadata(context: Context, document: DocumentFile): TrackMetadata {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, document.uri)
      fun metadata(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf(String::isNotBlank)
      TrackMetadata(
        durationMs = metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        title = metadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
        artist = metadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
          ?: metadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
        album = metadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
      )
    } catch (_: Exception) {
      TrackMetadata()
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun isCoverArtworkName(value: String): Boolean {
    val normalized = value.lowercase(Locale.ROOT)
    val extension = normalized.substringAfterLast('.', "")
    val baseName = normalized.substringBeforeLast('.', "")
    return baseName in setOf("cover", "folder", "front", "artwork", "album") &&
      extension in setOf("jpg", "jpeg", "png", "webp")
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
