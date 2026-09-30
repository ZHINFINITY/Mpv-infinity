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
import app.infinity.mpvz.database.entities.AudiobookEntity
import app.infinity.mpvz.domain.audiobook.AudiobookFolderScanner
import app.infinity.mpvz.domain.audiobook.AudiobookFolderTrack
import app.infinity.mpvz.domain.audiobook.AudiobookFolderTree
import app.infinity.mpvz.ui.player.AudiobookPlayback
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.preferences.preference.PreferenceStore
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

private val AUDIO_EXTENSIONS = setOf("mp3", "m4b", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "ape", "mka", "aax", "aaxc")

class AudiobookLibraryViewModel(application: Application) : AndroidViewModel(application) {
  private val dao = GlobalContext.get().get<AudiobookDao>()
  private val preferences = GlobalContext.get().get<PreferenceStore>()
  private val selectedFolderPreference = preferences.getString("audiobook_selected_folder_uri", "")
  private val _selectedFolderPath = MutableStateFlow<String?>(null)
  val selectedFolderPath = _selectedFolderPath.asStateFlow()
  val library = dao.observeLibrary()
    .map { list -> list.filter { !it.book.sourceKey.startsWith("abs:") } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
  private val _error = MutableStateFlow<String?>(null)
  val error = _error.asStateFlow()
  private val _selectedFolderName = MutableStateFlow("")
  val selectedFolderName = _selectedFolderName.asStateFlow()
  private var importJob: Job? = null

  init {
    _selectedFolderName.value = selectedFolderPreference.get().takeIf(String::isNotBlank)?.let { uri ->
      DocumentFile.fromTreeUri(application, Uri.parse(uri))?.name.orEmpty()
    }.orEmpty()
    _selectedFolderPath.value = selectedFolderPreference.get().takeIf(String::isNotBlank)?.let { safPath(Uri.parse(it)) }
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

  fun chooseFolder(uri: Uri) {
    val context = getApplication<Application>()
    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    selectedFolderPreference.set(uri.toString())
    _selectedFolderName.value = DocumentFile.fromTreeUri(context, uri)?.name.orEmpty()
    _selectedFolderPath.value = safPath(uri)
    importFiles(emptyList(), uri)
  }

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

  fun importFiles(uris: List<Uri>, folder: Uri? = null) {
    if (importJob?.isActive == true || uris.isEmpty() && folder == null) return
    _error.value = null
    _progress.value = 0 to 0
    importJob = viewModelScope.launch(Dispatchers.IO) {
      val context = getApplication<Application>()
      try {
        (listOfNotNull(folder) + uris).distinct().forEach { uri ->
          ensurePersistedReadPermission(context, uri)
        }
        if (folder != null) {
          AudiobookImporter(context, dao).importFolderAsBooks(folder) { current, total -> _progress.value = current to total }
        } else {
          AudiobookImporter(context, dao).importBook(uris) { current, total -> _progress.value = current to total }
        }
        if (folder != null) {
          AudiobookImporter(context, dao).importFolderAsBooks(folder) { current, total -> _progress.value = current to total }
        } else {
          AudiobookImporter(context, dao).importBook(uris) { current, total -> _progress.value = current to total }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        _error.value = context.getString(R.string.audiobook_import_failed, failure.localizedMessage.orEmpty())
      } finally {
        _progress.value = null
      }
    }
  }

  fun scanLocalStorage() {
    if (importJob?.isActive == true) return
    _error.value = null
    _progress.value = 0 to 0
    importJob = viewModelScope.launch(Dispatchers.IO) {
      val context = getApplication<Application>()
      try {
        AudiobookImporter(context, dao).scanLocalStorage { current, total -> _progress.value = current to total }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        _error.value = context.getString(R.string.audiobook_import_failed, failure.localizedMessage.orEmpty())
      } finally {
        _progress.value = null
      }
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
}
