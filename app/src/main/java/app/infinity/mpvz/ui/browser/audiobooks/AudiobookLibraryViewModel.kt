package app.infinity.mpvz.ui.browser.audiobooks

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.infinity.mpvz.R
import app.infinity.mpvz.database.dao.AudiobookDao
import app.infinity.mpvz.database.entities.AudiobookEntity
import app.infinity.mpvz.domain.audiobook.AudiobookFolderScanner
import app.infinity.mpvz.domain.audiobook.AudiobookFolderTrack
import app.infinity.mpvz.domain.audiobook.AudiobookFolderTree
import app.infinity.mpvz.ui.player.AudiobookPlayback
import app.infinity.mpvz.ui.player.PlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.IOException

internal fun hasDurableAudiobookReadAccess(
  uriScheme: String?,
  persistedReadPermission: Boolean,
): Boolean = !uriScheme.equals("content", ignoreCase = true) || persistedReadPermission

class AudiobookLibraryViewModel(application: Application) : AndroidViewModel(application) {
  private val dao = GlobalContext.get().get<AudiobookDao>()
  val library = dao.observeLibrary()
    .map { list -> list.filter { !it.book.sourceKey.startsWith("abs:") } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
  private val _error = MutableStateFlow<String?>(null)
  val error = _error.asStateFlow()
  private val folderPreferences = application.getSharedPreferences("audiobook_folder_browser", Context.MODE_PRIVATE)
  private val _folderTree = MutableStateFlow<AudiobookFolderTree?>(null)
  internal val folderTree = _folderTree.asStateFlow()
  private val _folderLoading = MutableStateFlow(false)
  val folderLoading = _folderLoading.asStateFlow()
  private val _folderError = MutableStateFlow<String?>(null)
  val folderError = _folderError.asStateFlow()
  private var folderScanJob: Job? = null
  private var folderScanGeneration = 0L

  init {
    viewModelScope.launch(Dispatchers.IO) {
      app.infinity.mpvz.domain.audiobook.AudiobookMarkerUtils.syncKnownAudiobooks(application, dao)
    }
    folderPreferences.getString(FOLDER_TREE_URI_KEY, null)?.let { savedUri ->
      loadFolderTree(Uri.parse(savedUri), persistPermission = false, saveSelection = false)
    }
  }

  fun selectFolder(uri: Uri) {
    loadFolderTree(uri, persistPermission = true, saveSelection = true)
  }

  fun refreshFolder() {
    val savedUri = folderPreferences.getString(FOLDER_TREE_URI_KEY, null) ?: return
    loadFolderTree(Uri.parse(savedUri), persistPermission = false, saveSelection = false)
  }

  private fun loadFolderTree(uri: Uri, persistPermission: Boolean, saveSelection: Boolean) {
    folderScanJob?.cancel()
    val generation = ++folderScanGeneration
    _folderError.value = null
    _folderLoading.value = true
    if (saveSelection) _folderTree.value = null
    folderScanJob = viewModelScope.launch(Dispatchers.IO) {
      val context = getApplication<Application>()
      try {
        if (persistPermission) ensurePersistedReadPermission(context, uri)
        val tree = AudiobookFolderScanner.scan(context, uri.toString())
        if (generation != folderScanGeneration) return@launch
        if (saveSelection) folderPreferences.edit().putString(FOLDER_TREE_URI_KEY, uri.toString()).apply()
        _folderTree.value = tree
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        if (generation == folderScanGeneration) {
          _folderError.value = failure.localizedMessage ?: context.getString(R.string.audiobook_folder_unavailable)
        }
      } finally {
        if (generation == folderScanGeneration) _folderLoading.value = false
      }
    }
  }

  private fun ensurePersistedReadPermission(context: Context, uri: Uri) {
    fun hasPersistedReadPermission(): Boolean =
      context.contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isReadPermission
      }

    if (hasDurableAudiobookReadAccess(uri.scheme, hasPersistedReadPermission())) return
    runCatching {
      context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.getOrElse { failure ->
      if (!hasPersistedReadPermission()) throw failure
    }
    if (!hasDurableAudiobookReadAccess(uri.scheme, hasPersistedReadPermission())) {
      throw SecurityException("Persistent read access is unavailable for the selected audiobook source")
    }
  }

  internal suspend fun resolveDirectAudioFiles(uris: List<Uri>): List<AudiobookFolderTrack> = withContext(Dispatchers.IO) {
    val context = getApplication<Application>()
    val documents = uris.distinct().mapNotNull { uri ->
      val document = DocumentFile.fromSingleUri(context, uri) ?: return@mapNotNull null
      if (!document.isFile || !AudiobookFolderScanner.isSupportedAudio(document.name, document.type)) null
      else uri to document
    }
    if (documents.isEmpty()) throw IOException(context.getString(R.string.audiobook_no_audio))
    documents.forEach { (uri, document) ->
      ensurePersistedReadPermission(context, uri)
      if (!document.canRead()) throw SecurityException(context.getString(R.string.audiobook_folder_unavailable))
    }
    documents.map { (uri, document) ->
      AudiobookFolderTrack(
        uri = uri.toString(),
        name = document.name?.takeIf(String::isNotBlank) ?: context.getString(R.string.audiobook_audio_file),
        mimeType = document.type?.takeIf(String::isNotBlank),
        sizeBytes = document.length().coerceAtLeast(0L),
      )
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
    const val FOLDER_TREE_URI_KEY = "selected_tree_uri"
  }
}
