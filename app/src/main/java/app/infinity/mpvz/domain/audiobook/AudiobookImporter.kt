package app.infinity.mpvz.domain.audiobook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import app.infinity.mpvz.R
import app.infinity.mpvz.database.dao.AudiobookDao
import app.infinity.mpvz.database.entities.AudiobookChapterEntity
import app.infinity.mpvz.database.entities.AudiobookEntity
import app.infinity.mpvz.database.entities.AudiobookTrackEntity
import app.infinity.mpvz.utils.media.openPersistedTreeDocument
import app.infinity.mpvz.utils.sort.SortUtils
import app.infinity.mpvz.utils.storage.FileTypeUtils
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.w3c.dom.Element

internal class AudiobookImporter(private val context: Context, private val dao: AudiobookDao) {
  private data class Source(val document: DocumentFile, val path: String)
  private data class Scanned(val source: Source, val track: AudiobookTrackEntity, val tags: Map<String, String>, val disc: Int, val number: Int)
  private data class AudioBookGroup(val key: String, val title: String, val sources: List<Source>)
  private data class FolderImportPlan(
    val folder: DocumentFile,
    val stopAtDirectories: Set<String>,
    val audioUriFilter: Set<String>? = null,
    val sourceKeyOverride: String? = null,
    val titleOverride: String? = null,
  )

  suspend fun scanLocalStorage(onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
    val candidates = discoverBookRoots(Environment.getExternalStorageDirectory())
    candidates.forEachIndexed { index, directory ->
      currentCoroutineContext().ensureActive()
      onProgress(index + 1, candidates.size)
      importBook(emptyList(), Uri.fromFile(directory)) { _, _ -> }
    }
  }

  suspend fun importFolderAsBooks(folder: Uri, onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
    val root = openPersistedTreeDocument(context, folder.toString()) ?: throw IOException(folder.toString())
    val books = discoverBookFolders(root)
    if (books.isEmpty()) throw IOException(context.getString(R.string.audiobook_no_audio))
    val bookUris = books.mapTo(mutableSetOf()) { it.uri.toString() }
    val plans = mutableListOf<FolderImportPlan>()
    books.forEach { bookFolder ->
      val folderUri = bookFolder.uri.toString()
      val stopAtDirectories = bookUris - folderUri
      val children = bookFolder.listFiles().toList()
      val hasBookMetadata = children.any(::isBookMetadata)
      val directAudio = children.filter(::isAudio)
      if (!hasBookMetadata && directAudio.size > 1) {
        val groups = groupAudioFiles(directAudio.map { Source(it, it.name.orEmpty()) })
        val canonicalFolderKey = AudiobookSourceIdentity.key(Uri.parse(folderUri))
        val oldBookId = dao.findBySource(canonicalFolderKey) ?: dao.findBySource(folderUri)
        val oldBook = oldBookId?.let { dao.getBook(it) }
        val previousFirstTrackIdentity = oldBook?.orderedTracks?.firstOrNull()?.uri
          ?.let { AudiobookSourceIdentity.key(Uri.parse(it)) }
        val primaryGroup = groups.indexOfFirst { group ->
          group.sources.any { AudiobookSourceIdentity.key(it.document.uri) == previousFirstTrackIdentity }
        }
          .takeIf { it >= 0 } ?: 0
        groups.forEachIndexed { groupIndex, group ->
          val groupIdentity = group.sources.map { AudiobookSourceIdentity.key(it.document.uri) }.sorted().joinToString("\n")
          val sourceKey = if (groupIndex == primaryGroup) canonicalFolderKey else "$canonicalFolderKey#audiobook-${digest(groupIdentity)}"
          plans += FolderImportPlan(
            folder = bookFolder,
            stopAtDirectories = stopAtDirectories,
            audioUriFilter = group.sources.mapTo(mutableSetOf()) { it.document.uri.toString() },
            sourceKeyOverride = sourceKey,
            titleOverride = group.title,
          )
        }
      } else {
        plans += FolderImportPlan(bookFolder, stopAtDirectories)
      }
    }

    plans.forEachIndexed { index, plan ->
      currentCoroutineContext().ensureActive()
      onProgress(index + 1, plans.size)
      // The child is reachable through the persisted permission on the chosen
      // root, even when the child URI itself is not persisted.
      importBook(
        emptyList(), plan.folder.uri, folderDocument = plan.folder,
        stopAtDirectories = plan.stopAtDirectories,
        audioUriFilter = plan.audioUriFilter,
        sourceKeyOverride = plan.sourceKeyOverride,
        titleOverride = plan.titleOverride,
      ) { _, _ -> }
    }
  }

  suspend fun importBook(
    uris: List<Uri>,
    folder: Uri? = null,
    folderDocument: DocumentFile? = null,
    stopAtDirectories: Set<String> = emptySet(),
    audioUriFilter: Set<String>? = null,
    sourceKeyOverride: String? = null,
    titleOverride: String? = null,
    onProgress: (Int, Int) -> Unit,
  ): Long = withContext(Dispatchers.IO) {
    val root = folderDocument ?: folder?.let { uri ->
      openPersistedTreeDocument(context, uri.toString())
        ?: DocumentFile.fromTreeUri(context, uri)
        ?: DocumentFile.fromSingleUri(context, uri)
        ?: uri.path?.let { path -> DocumentFile.fromFile(File(path)) }
        ?: throw IOException(uri.toString())
    }
    // A chosen folder is a read-only library source. Never create marker files or alter its hierarchy.
    if (folder == null) AudiobookMarkerUtils.ensureMarker(context, folder, root)
    val sources = if (root != null) collectFiles(root, stopAtDirectories) else uris.distinct().map { uri ->
      val file = DocumentFile.fromSingleUri(context, uri) ?: throw IOException(uri.toString())
      AudiobookMarkerUtils.ensureMarker(context, uri, file)
      Source(file, file.name.orEmpty())
    }
    val audio = sources.filter { source ->
      val extension = source.document.name?.substringAfterLast('.', "")?.lowercase()
      (extension in FileTypeUtils.AUDIO_EXTENSIONS || source.document.type?.startsWith("audio/") == true) &&
        (audioUriFilter == null || source.document.uri.toString() in audioUriFilter)
    }
    if (audio.isEmpty()) throw IOException(context.getString(R.string.audiobook_no_audio))
    val sourceKey = sourceKeyOverride ?: folder?.let(AudiobookSourceIdentity::key)
      ?: digest(audio.map { AudiobookSourceIdentity.key(it.document.uri) }.sorted().joinToString("\n"))
    val metadata = readMetadata(sources)
    var coverUri = sources.firstOrNull {
      it.document.name?.lowercase() in setOf("cover.jpg", "cover.png", "folder.jpg", "folder.png")
    }?.document?.uri?.toString()
    val scanned = audio.mapIndexed { index, source ->
      currentCoroutineContext().ensureActive()
      onProgress(index + 1, audio.size)
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(context, source.document.uri)
        fun tag(key: Int): String = retriever.extractMetadata(key)?.trim().orEmpty()
        val duration = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0
        if (duration <= 0) throw IOException(context.getString(R.string.audiobook_unreadable, source.path))
        if (coverUri == null) coverUri = saveCover(retriever.embeddedPicture, sourceKey)
        Scanned(
          source,
          AudiobookTrackEntity(
            bookId = 0, uri = source.document.uri.toString(), fileName = source.path,
            title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { source.document.name.orEmpty().substringBeforeLast('.') },
            position = index, durationMs = duration, size = source.document.length(),
          ),
          mapOf(
            "title" to tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            "author" to tag(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifBlank { tag(MediaMetadataRetriever.METADATA_KEY_AUTHOR) },
            "narrator" to tag(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
            "genre" to tag(MediaMetadataRetriever.METADATA_KEY_GENRE),
            "publishedYear" to tag(MediaMetadataRetriever.METADATA_KEY_YEAR),
          ),
          tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
          tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
        )
      } catch (failure: Exception) {
        throw IOException(context.getString(R.string.audiobook_unreadable, source.path), failure)
      } finally {
        runCatching { retriever.release() }
      }
    }
    val numbered = scanned.all { it.number > 0 }
    val ordered = scanned.sortedWith { first, second ->
      val disc = if (numbered) first.disc.compareTo(second.disc) else 0
      val track = if (numbered && disc == 0) first.number.compareTo(second.number) else 0
      when {
        disc != 0 -> disc
        track != 0 -> track
        else -> SortUtils.NaturalOrderComparator.DEFAULT.compare(first.source.path, second.source.path)
      }
    }
    fun field(key: String): String = metadata.text(key).ifBlank { ordered.firstNotNullOfOrNull { it.tags[key]?.takeIf(String::isNotBlank) }.orEmpty() }
    val seriesValue = metadata.optJSONArray("series")?.opt(0)
    val series = seriesValue as? JSONObject
    val parsedTitle = field("title").ifBlank { titleOverride?.takeIf(String::isNotBlank) ?: root?.name ?: ordered.first().track.title }
    val parsedAuthor = metadata.names("authors").ifBlank { field("author") }

    if (coverUri == null && parsedTitle.isNotBlank()) {
      runCatching {
        val onlineMatch = AudiobookCoverFetcher.search(parsedTitle, parsedAuthor).firstOrNull()
        if (onlineMatch != null) {
          coverUri = AudiobookCoverFetcher.downloadAndSaveCover(context, onlineMatch.coverUrl, sourceKey)
        }
      }
    }

    val book = AudiobookEntity(
      sourceKey = sourceKey,
      title = parsedTitle,
      subtitle = metadata.text("subtitle"),
      author = parsedAuthor,
      narrator = metadata.names("narrators").ifBlank { field("narrator") },
      series = series?.text("name") ?: (seriesValue as? String) ?: metadata.text("series"),
      seriesPart = series?.text("sequence") ?: metadata.text("seriesPart"),
      description = metadata.text("description"), genre = metadata.names("genres").ifBlank { field("genre") },
      language = metadata.text("language"), publisher = metadata.text("publisher"),
      publishedYear = field("publishedYear"), isbn = metadata.text("isbn"), asin = metadata.text("asin"),
      abridged = if (metadata.has("abridged") && !metadata.isNull("abridged")) metadata.optBoolean("abridged") else null,
      coverUri = coverUri,
    )
    val id = importOrReconcileDuplicate(book, ordered.map { it.track })
    metadata.optJSONArray("chapters")?.let { chapterData ->
      var offset = 0L
      dao.getBook(id)?.orderedTracks?.forEach { track ->
        val chapters = (0 until chapterData.length()).mapNotNull { chapterIndex ->
          val chapter = chapterData.optJSONObject(chapterIndex) ?: return@mapNotNull null
          val start = (chapter.optDouble("start", Double.NaN) * 1000).takeIf(Double::isFinite)?.toLong() ?: return@mapNotNull null
          val end = (chapter.optDouble("end", Double.NaN) * 1000).takeIf(Double::isFinite)?.toLong() ?: return@mapNotNull null
          val localStart = (start - offset).coerceAtLeast(0)
          val localEnd = (end - offset).coerceAtMost(track.durationMs)
          if (localEnd <= localStart) null else AudiobookChapterEntity(track.id, localStart, localEnd,
            chapter.text("title").ifBlank { context.getString(R.string.audiobook_chapter_number, chapterIndex + 1) })
        }
        if (chapters.isNotEmpty()) dao.replaceChapters(track.id, chapters)
        offset += track.durationMs
      }
    }
    AudiobookMarkerUtils.clearCache()
    app.infinity.mpvz.utils.media.MediaLibraryEvents.notifyChanged()
    id
  }

  private suspend fun importOrReconcileDuplicate(book: AudiobookEntity, tracks: List<AudiobookTrackEntity>): Long {
    if (dao.findBySource(book.sourceKey) != null) return dao.importBook(book, tracks)

    val incomingIdentities = tracks.map { AudiobookSourceIdentity.key(Uri.parse(it.uri)) }.sorted()
    val localBooks = dao.getAllBooks()
      .asSequence()
      .filterNot { it.sourceKey.startsWith("abs:") }
      .toList()
    val localBookIds = localBooks
      .asSequence()
      .map { it.id }
      .toSet()
    val matchingIds = dao.getAllTracks()
      .asSequence()
      .filter { it.bookId in localBookIds }
      .groupBy { it.bookId }
      .filterValues { existing ->
        existing.size == incomingIdentities.size &&
          existing.map { AudiobookSourceIdentity.key(Uri.parse(it.uri)) }.sorted() == incomingIdentities
      }
      .keys
    val existingBook = localBooks
      .asSequence()
      .filter { it.id in matchingIds }
      .maxWithOrNull(compareBy<AudiobookEntity> { it.lastPlayedAt }.thenBy { it.progressMs }.thenBy { it.addedAt })

    return if (existingBook != null) {
      dao.reconcileImportedBook(existingBook.id, book.sourceKey, tracks)
    } else {
      dao.importBook(book, tracks)
    }
  }

  private suspend fun collectFiles(root: DocumentFile, stopAtDirectories: Set<String> = emptySet()): List<Source> {
    val result = mutableListOf<Source>()
    val pending = ArrayDeque<Source>().apply { add(Source(root, "")) }
    val visited = mutableSetOf<String>()
    while (pending.isNotEmpty()) {
      currentCoroutineContext().ensureActive()
      val parent = pending.removeFirst()
      if (!visited.add(parent.document.uri.toString())) continue
      if (!parent.document.canRead()) throw IOException(parent.path)
      parent.document.listFiles().forEach { child ->
        val entry = Source(child, "${parent.path}/${child.name.orEmpty()}")
        if (child.isDirectory) {
          if (child.uri.toString() !in stopAtDirectories) pending.add(entry)
        } else if (child.isFile) result.add(entry)
      }
    }
    return result
  }

  private fun isAudio(document: DocumentFile): Boolean = document.isFile && (
    document.type?.startsWith("audio/") == true ||
      document.name?.substringAfterLast('.', "")?.lowercase() in FileTypeUtils.AUDIO_EXTENSIONS
  )

  private fun isBookMetadata(document: DocumentFile): Boolean = document.isFile && (
    document.name?.equals("metadata.json", true) == true || document.name?.endsWith(".opf", true) == true
  )

  private fun groupAudioFiles(files: List<Source>): List<AudioBookGroup> {
    val genericAlbumNames = setOf("unknown", "audiobook", "audio book", "track", "untitled")
    val tagged = files.map { source ->
      val album = runCatching {
        MediaMetadataRetriever().let { retriever ->
          try {
            retriever.setDataSource(context, source.document.uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim().orEmpty()
          } finally {
            retriever.release()
          }
        }
      }.getOrDefault("")
      val usableAlbum = album.takeIf { it.isNotBlank() && normalizeBookTitle(it) !in genericAlbumNames }
      val title = cleanBookTitle(usableAlbum ?: source.document.name.orEmpty()).ifBlank { source.document.name.orEmpty() }
      val key = normalizeBookTitle(title).ifBlank { source.document.uri.toString() }
      Triple(key, title, source)
    }
    return tagged.groupBy { it.first }.map { (key, group) ->
      AudioBookGroup(key, group.first().second, group.map { it.third })
    }.sortedBy { it.key }
  }

  private fun cleanBookTitle(value: String): String = value.substringBeforeLast('.', value)
    .replace(Regex("(?i)[\\s._-]*\\d+(?:\\.\\d+)?\\s*(?:kb(?:ps?)?|kbit(?:/s)?|khz|mb(?:ps?)?)\\b.*$"), "")
    .replace(Regex("(?i)[\\s._-]*(?:part|pt|disc|disk|cd|track|chapter)[\\s._-]*\\d+$"), "")
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .replace('_', ' ')
    .replace('-', ' ')
    .replace(Regex("\\s+"), " ")
    .trim()

  private fun normalizeBookTitle(value: String): String = cleanBookTitle(value)
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

  private fun discoverBookFolders(root: DocumentFile): List<DocumentFile> {
    data class Directory(
      val document: DocumentFile,
      val parentUri: String?,
      val childUris: List<String>,
      val hasAudio: Boolean,
      val hasMetadata: Boolean,
    )

    val directories = linkedMapOf<String, Directory>()
    val pending = ArrayDeque<Pair<DocumentFile, String?>>().apply { add(root to null) }
    while (pending.isNotEmpty()) {
      val (directory, parentUri) = pending.removeFirst()
      val uri = directory.uri.toString()
      if (uri in directories) continue
      val children = directory.listFiles().toList()
      val childDirectories = children.filter { it.isDirectory && !it.name.orEmpty().startsWith(".") }
      val hasAudio = children.any { child ->
        child.isFile && (child.type?.startsWith("audio/") == true ||
          child.name?.substringAfterLast('.', "")?.lowercase() in FileTypeUtils.AUDIO_EXTENSIONS)
      }
      val hasMetadata = children.any { child ->
        child.isFile && (child.name?.equals("metadata.json", true) == true || child.name?.endsWith(".opf", true) == true)
      }
      directories[uri] = Directory(directory, parentUri, childDirectories.map { it.uri.toString() }, hasAudio, hasMetadata)
      childDirectories.forEach { pending.addLast(it to uri) }
    }

    fun hasMetadataAncestor(directory: Directory): Boolean {
      var parent = directory.parentUri?.let(directories::get)
      while (parent != null) {
        if (parent.hasMetadata) return true
        parent = parent.parentUri?.let(directories::get)
      }
      return false
    }

    // An explicit book metadata file owns its otherwise-unlabelled child folders
    // (for example, CD1/CD2). Audio-bearing folders without such an ancestor are
    // separate books, even when another book also has files at the selected root.
    val candidates = directories.values.filter { it.hasMetadata || it.hasAudio && !hasMetadataAncestor(it) }
    val candidateUris = candidates.mapTo(mutableSetOf()) { it.document.uri.toString() }

    fun containsAudioOutsideNestedBooks(rootDirectory: Directory): Boolean {
      val search = ArrayDeque<Directory>().apply { add(rootDirectory) }
      val visited = mutableSetOf<String>()
      while (search.isNotEmpty()) {
        val current = search.removeFirst()
        val currentUri = current.document.uri.toString()
        if (!visited.add(currentUri)) continue
        if (current.hasAudio) return true
        current.childUris.forEach { childUri ->
          if (childUri !in candidateUris) directories[childUri]?.let(search::addLast)
        }
      }
      return false
    }

    return candidates.filter(::containsAudioOutsideNestedBooks).map { it.document }.ifEmpty {
      listOf(root)
    }
  }

  private fun discoverBookRoots(storage: File): List<File> {
    val result = mutableListOf<File>()
    val pending = ArrayDeque<File>().apply { add(storage) }
    while (pending.isNotEmpty()) {
      val directory = pending.removeFirst()
      val children = directory.listFiles() ?: continue
      val hasAudio = children.any { it.isFile && it.extension.lowercase() in FileTypeUtils.AUDIO_EXTENSIONS }
      val hasBookMetadata = children.any { it.isFile && (it.name.equals("metadata.json", true) || it.extension.equals("opf", true)) }
      if (hasAudio || hasBookMetadata) result += directory
      children.filter { it.isDirectory && !it.name.startsWith(".") }.forEach(pending::addLast)
    }
    // A metadata-bearing/audio-bearing parent represents the book; avoid duplicate nested books.
    return result.filter { directory -> result.none { other -> other != directory && directory.parentFile == other } }
  }

  private fun readMetadata(files: List<Source>): JSONObject {
    val source = files.firstOrNull { it.document.name.equals("metadata.json", true) }
      ?: files.firstOrNull { it.document.name?.endsWith(".opf", true) == true }
      ?: return JSONObject()
    return runCatching {
      val text = context.contentResolver.openInputStream(source.document.uri)?.bufferedReader()?.use { reader ->
        val buffer = CharArray(4096)
        buildString {
          while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (length + count > 1024 * 1024) throw IOException("Metadata is too large")
            append(buffer, 0, count)
          }
        }
      } ?: return JSONObject()
      if (source.document.name?.endsWith(".json", true) == true) {
        val json = JSONObject(text)
        json.optJSONObject("metadata") ?: json
      } else {
        readOpf(text)
      }
    }.getOrDefault(JSONObject())
  }

  private fun readOpf(text: String): JSONObject {
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      setFeature("http://xml.org/sax/features/external-general-entities", false)
      setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val document = factory.newDocumentBuilder().parse(text.byteInputStream())
    fun elements(name: String): List<Element> {
      val nodes = document.getElementsByTagNameNS("*", name)
      return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }
    return JSONObject().apply {
      mapOf("title" to "title", "description" to "description", "language" to "language", "publisher" to "publisher",
        "date" to "publishedYear", "subject" to "genre").forEach { (source, target) ->
        put(target, elements(source).joinToString(", ") { it.textContent.trim() })
      }
      val creators = elements("creator")
      fun role(element: Element) = element.getAttributeNS("http://www.idpf.org/2007/opf", "role").ifBlank { element.getAttribute("role") }
      put("author", creators.filter { role(it) in setOf("", "aut") }.joinToString(", ") { it.textContent.trim() })
      put("narrator", creators.filter { role(it) == "nrt" }.joinToString(", ") { it.textContent.trim() })
      elements("meta").forEach {
        when (it.getAttribute("name")) {
          "calibre:series" -> put("series", it.getAttribute("content"))
          "calibre:series_index" -> put("seriesPart", it.getAttribute("content"))
        }
      }
      elements("identifier").forEach {
        val scheme = it.getAttributeNS("http://www.idpf.org/2007/opf", "scheme").ifBlank { it.getAttribute("scheme") }
        if (scheme.equals("isbn", true)) put("isbn", it.textContent.trim())
        if (scheme.equals("asin", true)) put("asin", it.textContent.trim())
      }
    }
  }

  private fun saveCover(bytes: ByteArray?, sourceKey: String): String? {
    if (bytes == null || bytes.size > 16 * 1024 * 1024) return null
    return runCatching {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 800) inSampleSize *= 2
      }
      val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
      try {
        val file = File(context.filesDir, "audiobook_covers/${digest(sourceKey)}.jpg")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        Uri.fromFile(file).toString()
      } finally {
        bitmap.recycle()
      }
    }.getOrNull()
  }

  private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "").trim()
  private fun JSONObject.names(key: String): String = optJSONArray(key)?.let { values ->
    (0 until values.length()).mapNotNull { index ->
      when (val value = values.opt(index)) {
        is String -> value
        is JSONObject -> value.text("name")
        else -> null
      }?.takeIf(String::isNotBlank)
    }.joinToString(", ")
  } ?: text(key)
  private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
