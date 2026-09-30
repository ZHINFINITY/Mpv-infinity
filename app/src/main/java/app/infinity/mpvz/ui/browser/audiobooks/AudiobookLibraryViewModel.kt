package app.infinity.mpvz.ui.browser.audiobooks

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.infinity.mpvz.R
import app.infinity.mpvz.database.dao.AudiobookDao
import app.infinity.mpvz.database.entities.Audiobook
import app.infinity.mpvz.database.entities.AudiobookEntity
import app.infinity.mpvz.database.entities.AudiobookTrackEntity
import app.infinity.mpvz.domain.audiobook.calculateDirectAudiobookProgress
import app.infinity.mpvz.domain.audiobook.directAudiobookFolderIdentity
import app.infinity.mpvz.domain.audiobook.stableVirtualAudiobookId
import app.infinity.mpvz.domain.audiobook.AudiobookFolderScanner
import app.infinity.mpvz.domain.audiobook.AudiobookFolderTrack
import app.infinity.mpvz.domain.audiobook.AudiobookFolderTree
import app.infinity.mpvz.domain.playbackstate.repository.PlaybackStateRepository
import app.infinity.mpvz.ui.player.AudiobookPlayback
import app.infinity.mpvz.ui.player.PlaybackIdentity
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.preferences.preference.PreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

internal data class DirectAudiobookLibrarySource(
  val folderUri: String,
  val tracks: List<AudiobookFolderTrack>,
)

class AudiobookLibraryViewModel(application: Application) : AndroidViewModel(application) {
  private val dao = GlobalContext.get().get<AudiobookDao>()
  private val preferences = GlobalContext.get().get<PreferenceStore>()
  private val playbackStateRepository = GlobalContext.get().get<PlaybackStateRepository>()
  private val selectedFolderPreference = preferences.getString("audiobook_selected_folder_uri", "")
  private val _selectedFolderPath = MutableStateFlow<String?>(null)
  val selectedFolderPath = _selectedFolderPath.asStateFlow()
  private val _virtualBooks = MutableStateFlow<List<Audiobook>>(emptyList())
  private val _directBookSources = MutableStateFlow<Map<String, DirectAudiobookLibrarySource>>(emptyMap())
  private val _libraryFolderTrees = MutableStateFlow<List<AudiobookFolderTree>>(emptyList())
  val library = combine(dao.observeLibrary(), _virtualBooks) { persisted, direct ->
    persisted.filterNot { it.book.sourceKey.startsWith("abs:") } + direct
  }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
  private val _error = MutableStateFlow<String?>(null)
  val error = _error.asStateFlow()
  private val _selectedFolderName = MutableStateFlow("")
  val selectedFolderName = _selectedFolderName.asStateFlow()
  private val folderPreferences = application.getSharedPreferences(FOLDER_PREFERENCES_NAME, Context.MODE_PRIVATE)
  private val _folderTree = MutableStateFlow<AudiobookFolderTree?>(null)
  internal val folderTree = _folderTree.asStateFlow()
  private val _folderError = MutableStateFlow<String?>(null)
  val folderError = _folderError.asStateFlow()
  private val _folderLoading = MutableStateFlow(false)
  val folderLoading = _folderLoading.asStateFlow()
  private var folderScanJob: Job? = null
  private val folderScanGeneration = AtomicLong()

  init {
    _selectedFolderName.value = selectedFolderPreference.get().takeIf(String::isNotBlank)?.let { uri ->
      DocumentFile.fromTreeUri(application, Uri.parse(uri))?.name.orEmpty()
    }.orEmpty()
    _selectedFolderPath.value = selectedFolderPreference.get().takeIf(String::isNotBlank)?.let { safPath(Uri.parse(it)) }
    viewModelScope.launch(Dispatchers.IO) {
      app.infinity.mpvz.domain.audiobook.AudiobookMarkerUtils.syncKnownAudiobooks(application, dao)
    }
    val savedFolderUri = savedSafFolderUris().firstOrNull()
    savedFolderUri?.let { savedUri ->
      loadFolderTree(Uri.parse(savedUri), persistPermission = false, saveSelection = false)
    }
  }

  fun selectFolder(uri: Uri) {
    val context = getApplication<Application>()
    selectedFolderPreference.set(uri.toString())
    _selectedFolderName.value = DocumentFile.fromTreeUri(context, uri)?.name.orEmpty()
    _selectedFolderPath.value = safPath(uri)
    loadFolderTree(uri, persistPermission = true, saveSelection = true)
  }

  fun refreshFolder() {
    val savedUri = folderPreferences.getString(FOLDER_TREE_URI_KEY, null)
      ?: selectedFolderPreference.get().takeIf(String::isNotBlank)
      ?: return
    loadFolderTree(Uri.parse(savedUri), persistPermission = false, saveSelection = false)
  }

  private fun loadFolderTree(uri: Uri, persistPermission: Boolean, saveSelection: Boolean) {
    folderScanJob?.cancel()
    val generation = folderScanGeneration.incrementAndGet()
    _folderError.value = null
    _folderLoading.value = true
    if (saveSelection) _folderTree.value = null
    folderScanJob = viewModelScope.launch(Dispatchers.IO) {
      val context = getApplication<Application>()
      try {
        if (persistPermission) ensurePersistedReadPermission(context, uri)
        val tree = AudiobookFolderScanner.scan(context, uri.toString())
        if (generation != folderScanGeneration.get()) return@launch
        if (saveSelection) {
          val selectedTrees = savedSafFolderUris(uri.toString()).toSet()
          folderPreferences.edit()
            .putString(FOLDER_TREE_URI_KEY, uri.toString())
            .putStringSet(FOLDER_TREE_URIS_KEY, selectedTrees)
            .apply()
        }
        _folderTree.value = tree
        val libraryTrees = savedSafFolderUris(uri.toString()).mapNotNull { savedUri ->
          if (savedUri == uri.toString()) tree
          else runCatching { AudiobookFolderScanner.scan(context, savedUri) }.getOrNull()
        }
        if (generation != folderScanGeneration.get()) return@launch
        _libraryFolderTrees.value = libraryTrees
        refreshVirtualBooks(libraryTrees)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        if (generation == folderScanGeneration.get()) {
          _folderError.value = failure.localizedMessage ?: context.getString(R.string.audiobook_folder_unavailable)
        }
      } finally {
        if (generation == folderScanGeneration.get()) _folderLoading.value = false
      }
    }
  }

  fun chooseFolder(uri: Uri) = selectFolder(uri)

  private fun safPath(uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val separator = id.indexOf(':')
    if (separator <= 0) return null
    val volume = id.substring(0, separator)
    val relative = id.substring(separator + 1).trim('/').replace("%2F", "/", ignoreCase = true)
    return if (volume.equals("primary", true)) {
      "/storage/emulated/0" + if (relative.isBlank()) "" else "/$relative"
    } else {
      "/storage/$volume" + if (relative.isBlank()) "" else "/$relative"
    }
  }

  private fun savedSafFolderUris(activeUri: String? = null): List<String> = buildList {
    activeUri?.let(::add)
    folderPreferences.getString(FOLDER_TREE_URI_KEY, null)?.let(::add)
    addAll(folderPreferences.getStringSet(FOLDER_TREE_URIS_KEY, emptySet()).orEmpty())
    selectedFolderPreference.get().takeIf(String::isNotBlank)?.let(::add)
  }.distinct()

  internal fun directBookSource(sourceKey: String): DirectAudiobookLibrarySource? =
    _directBookSources.value[sourceKey]

  fun setDirectBookFinished(sourceKey: String, finished: Boolean) {
    if (sourceKey !in _directBookSources.value) return
    folderPreferences.edit().putBoolean(directFinishedPreferenceKey(sourceKey), finished).apply()
    viewModelScope.launch(Dispatchers.IO) { refreshVirtualBooks(_libraryFolderTrees.value) }
  }

  fun refreshVirtualProgress() {
    val trees = _libraryFolderTrees.value
    if (trees.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { refreshVirtualBooks(trees) }
  }

  private suspend fun refreshVirtualBooks(trees: List<AudiobookFolderTree>) {
    val states = runCatching { playbackStateRepository.getAllPlaybackStates() }.getOrDefault(emptyList())
      .associateBy { it.mediaTitle }
    val books = linkedMapOf<String, Pair<Audiobook, DirectAudiobookLibrarySource>>()
    trees.forEach { tree ->
      tree.bookListings().forEach bookLoop@{ listing ->
        val tracks = tree.tracksForBook(listing.folder.uri)
        if (tracks.isEmpty()) return@bookLoop
        val sourceKey = DIRECT_BOOK_SOURCE_PREFIX + directAudiobookFolderIdentity(listing.folder.uri)
        val savedPositions = tracks.associate { track ->
          track.uri to (states[PlaybackIdentity.forUri(track.uri)]?.lastPosition?.toLong()?.times(1000L) ?: 0L)
        }
        val savedDurations = tracks.mapNotNull { track ->
          val state = states[PlaybackIdentity.forUri(track.uri)] ?: return@mapNotNull null
          val durationMs = (state.lastPosition.toLong() + state.timeRemaining.toLong()).coerceAtLeast(0L) * 1000L
          track.uri to durationMs
        }.toMap()
        val finishKey = directFinishedPreferenceKey(sourceKey)
        val finishOverride = if (folderPreferences.contains(finishKey)) folderPreferences.getBoolean(finishKey, false) else null
        val progress = calculateDirectAudiobookProgress(tracks, savedPositions, savedDurations, finishOverride)
        val bookId = stableVirtualAudiobookId("book", sourceKey)
        val trackEntities = tracks.mapIndexed { index, track ->
          AudiobookTrackEntity(
            id = stableVirtualAudiobookId("track", track.uri),
            bookId = bookId,
            uri = track.uri,
            fileName = track.name,
            title = track.title?.takeIf(String::isNotBlank) ?: track.name.substringBeforeLast('.', track.name),
            position = index,
            durationMs = maxOf(track.durationMs, savedDurations[track.uri] ?: 0L),
            size = track.sizeBytes,
          )
        }
        val entity = AudiobookEntity(
          id = bookId,
          sourceKey = sourceKey,
          title = listing.folder.name,
          author = tracks.firstNotNullOfOrNull { it.artist?.takeIf(String::isNotBlank) }.orEmpty(),
          coverUri = listing.coverUri,
          addedAt = 0L,
          currentTrackId = trackEntities.getOrNull(progress.currentTrackIndex)?.id,
          positionMs = progress.currentTrackPositionMs,
          progressMs = progress.progressMs,
          finished = progress.finished,
        )
        books.putIfAbsent(sourceKey, Audiobook(entity, trackEntities) to DirectAudiobookLibrarySource(listing.folder.uri, tracks))
      }
    }
    _virtualBooks.value = books.values.map { it.first }
    _directBookSources.value = books.mapValues { it.value.second }
  }

  private fun directFinishedPreferenceKey(sourceKey: String): String =
    DIRECT_FINISHED_PREFIX + sourceKey.removePrefix(DIRECT_BOOK_SOURCE_PREFIX)

  internal suspend fun resolveDirectAudioFiles(uris: List<Uri>): List<AudiobookFolderTrack> = withContext(Dispatchers.IO) {
    val context = getApplication<Application>()
    val tracks = uris.distinct().mapNotNull { uri ->
      runCatching { ensurePersistedReadPermission(context, uri) }
      val document = DocumentFile.fromSingleUri(context, uri) ?: return@mapNotNull null
      if (!document.isFile || !AudiobookFolderScanner.isSupportedAudio(document.name, document.type)) return@mapNotNull null
      AudiobookFolderTrack(
        uri = uri.toString(),
        name = document.name?.takeIf(String::isNotBlank) ?: context.getString(R.string.audiobook_audio_file),
        mimeType = document.type?.takeIf(String::isNotBlank),
        sizeBytes = document.length().coerceAtLeast(0L),
      )
    }
    if (tracks.isEmpty()) throw IOException(context.getString(R.string.audiobook_no_audio))
    tracks
  }

  private fun ensurePersistedReadPermission(context: Context, uri: Uri) {
    val alreadyGranted = context.contentResolver.persistedUriPermissions.any {
      it.uri == uri && it.isReadPermission
    }
    if (!alreadyGranted) {
      context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  fun dismissError() { _error.value = null }

  fun remove(id: Long) = operation {
    if (PlaybackSession.state.value.currentItem?.audiobook?.bookId == id) {
      throw IllegalStateException(getApplication<Application>().getString(R.string.audiobook_stop_before_remove))
    }
    dao.deleteBook(id)
  }

  fun setFinished(id: Long, finished: Boolean) = operation {
    if (PlaybackSession.state.value.currentItem?.audiobook?.bookId == id) return@operation
    AudiobookPlayback.capture()
    AudiobookPlayback.flush()
    dao.setFinished(id, finished)
  }

  suspend fun searchOnlineCovers(title: String, author: String? = null) =
    app.infinity.mpvz.domain.audiobook.AudiobookCoverFetcher.search(title, author)

  fun edit(book: AudiobookEntity, newCoverUrl: String? = null) = operation {
    val context = getApplication<Application>()
    var finalCoverUri = book.coverUri
    if (!newCoverUrl.isNullOrBlank()) {
      val localUri = app.infinity.mpvz.domain.audiobook.AudiobookCoverFetcher.downloadAndSaveCover(
        context = context,
        coverUrl = newCoverUrl,
        sourceKey = book.sourceKey,
      )
      if (localUri != null) {
        finalCoverUri = localUri
      }
    }
    dao.updateDetails(
      id = book.id,
      title = book.title.trim(),
      author = book.author.trim(),
      narrator = book.narrator.trim(),
      series = book.series.trim(),
      seriesPart = book.seriesPart.trim(),
      coverUri = finalCoverUri,
    )
  }

  private fun operation(action: suspend () -> Unit) = viewModelScope.launch {
    try {
      action()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      _error.value = failure.localizedMessage.orEmpty()
    }
  }

  private companion object {
    const val FOLDER_PREFERENCES_NAME = "audiobook_folder_browser"
    const val FOLDER_TREE_URI_KEY = "folder_tree_uri"
    const val FOLDER_TREE_URIS_KEY = "folder_tree_uris"
    const val DIRECT_BOOK_SOURCE_PREFIX = "saf-folder:"
    const val DIRECT_FINISHED_PREFIX = "direct-book-finished:"
  }
}
