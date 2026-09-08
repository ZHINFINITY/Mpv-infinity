package app.infinity.mpvz.ui.browser.catalog

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import app.infinity.mpvz.catalog.CatalogViewModel
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.StreamOption
import app.infinity.mpvz.catalog.CatalogProvider
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.torrent.TorrentSelectionActivity
import app.infinity.mpvz.utils.media.MediaUtils
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.player.components.expressive.ExpressiveElevatedCard
import app.infinity.mpvz.ui.components.InlineSearchBar

@Composable
fun CatalogScreen() {
  val context = LocalContext.current
  val viewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.Factory(context.applicationContext as android.app.Application))
  val state by viewModel.state.collectAsState()
  var showSettings by remember { mutableStateOf(false) }
  var showStreamPicker by remember { mutableStateOf(false) }
  val featured = state.items.firstOrNull()
  val railItems = remember(state.items) {
    state.items.drop(1).groupBy { item ->
      when {
        item.provider == CatalogProvider.KITSU -> "Top Anime"
        item.type == app.infinity.mpvz.catalog.MediaType.TV -> "Popular Series"
        else -> "Trending Movies"
      }
    }.values.flatten().distinctBy { "${it.provider}:${it.providerId ?: it.id}" }.take(12)
  }

  LaunchedEffect(Unit) {
    viewModel.resolvedUrl.collect { url ->
      if (url != null) {
        context.startActivity(Intent(context, PlayerActivity::class.java).apply {
          action = Intent.ACTION_VIEW
          data = Uri.parse(url)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        viewModel.consumeResolvedUrl()
      }
    }
  }

  state.selectedItem?.let { item ->
    MediaDetailsSheet(
      item = item,
      streams = if (showStreamPicker) emptyList() else state.streamOptions,
      sourceFilter = state.sourceFilter,
      sourceSort = state.sourceSort,
      isLoading = state.resolvingId == item.id,
      onLoadSources = { showStreamPicker = true; viewModel.resolve(item, state.selectedSeason, state.selectedEpisode) },
      onEpisode = { season, episode -> showStreamPicker = true; viewModel.resolve(item, season, episode) },
      onFilter = viewModel::setSourceFilter,
      onSort = viewModel::setSourceSort,
      onSelect = { stream ->
        if (stream.isPlayable) viewModel.playStream(stream) else context.startActivity(Intent(context, TorrentSelectionActivity::class.java).apply {
          action = Intent.ACTION_VIEW
          data = Uri.parse(stream.url)
          putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, stream.url)
          putExtra(MediaUtils.EXTRA_MEDIA_TITLE, item.title)
          putExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION, item.overview)
          putExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL, item.posterUrl)
          putExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL, item.backdropUrl)
          stream.torrentFileIndex?.let { putExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, it) }
        })
      },
      onBack = viewModel::closeDetails,
    )
    if (showStreamPicker) {
      StreamPickerSheet(
        streams = state.streamOptions,
        loading = state.resolvingId == item.id,
        error = state.error,
        sourceFilter = state.sourceFilter,
        sourceSort = state.sourceSort,
        onFilter = viewModel::setSourceFilter,
        onSort = viewModel::setSourceSort,
        onSelect = { stream ->
          showStreamPicker = false
          if (stream.isPlayable) viewModel.playStream(stream) else context.startActivity(Intent(context, TorrentSelectionActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(stream.url)
            putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, stream.url)
            putExtra(MediaUtils.EXTRA_MEDIA_TITLE, item.title)
            putExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION, item.overview)
            putExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL, item.posterUrl)
            putExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL, item.backdropUrl)
            stream.torrentFileIndex?.let { putExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, it) }
          })
        },
        onDismiss = { showStreamPicker = false },
      )
    }
    return
  }

  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 96.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
      InlineSearchBar(
        query = state.query,
        onQueryChange = viewModel::setQuery,
        onSearch = viewModel::setQuery,
        modifier = Modifier.weight(1f),
        placeholder = { Text("Search movies and TV") },
        leadingIcon = { Icon(Icons.RoundedFilled.Search, "Search") },
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
      )
      IconButton(onClick = { showSettings = true }) { Icon(Icons.RoundedFilled.Settings, "Catalog settings") }
    }
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(
        selected = state.enabledProviders.size == CatalogProvider.entries.size,
        onClick = viewModel::enableAllProviders,
        label = { Text("All") },
      )
      CatalogProvider.entries.forEach { provider ->
        FilterChip(
          selected = provider in state.enabledProviders,
          onClick = { viewModel.toggleProvider(provider) },
          label = { Text(when (provider) {
            CatalogProvider.TMDB -> "TMDB"
            CatalogProvider.CINEMETA -> "Cinemeta"
            CatalogProvider.KITSU -> "Kitsu Anime"
          }) },
        )
      }
    }
    state.error?.let { CatalogStatusState(message = it, onRetry = viewModel::retry, onEdit = { showSettings = true }) }
    AnimatedContent(targetState = featured, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "catalogHero") { hero ->
      if (state.query.isBlank() && hero != null) CatalogHero(hero) else Spacer(Modifier.height(4.dp))
    }
    if (state.query.isBlank()) {
      listOf("Trending Movies", "Popular Series", "Top Anime").forEach { railTitle ->
        val items = railItems.filter { item ->
          when (railTitle) {
            "Top Anime" -> item.provider == CatalogProvider.KITSU
            "Popular Series" -> item.type == app.infinity.mpvz.catalog.MediaType.TV && item.provider != CatalogProvider.KITSU
            else -> item.type == app.infinity.mpvz.catalog.MediaType.MOVIE && item.provider != CatalogProvider.KITSU
          }
        }
        if (items.isNotEmpty()) {
          Text(railTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
          LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(items, key = { "rail-${it.provider}-${it.providerId ?: it.id}" }) { item ->
              Box(Modifier.width(130.dp)) { CatalogGridItem(item, state.resolvingId == item.id) { viewModel.openDetails(item) } }
            }
          }
        }
      }
    }
    if (state.isLoading) Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    if (!state.isLoading && state.items.isEmpty() && state.error == null) CatalogStatusState("No catalog items found", viewModel::retry) { showSettings = true }
    LazyVerticalGrid(
      columns = GridCells.Adaptive(130.dp),
      contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      itemsIndexed(state.items.filterNot { it in railItems }.drop(1), key = { _, item -> "${item.provider}-${item.providerId ?: item.id}" }) { index, item ->
        if (index >= state.items.size - railItems.size - 3) viewModel.loadMore()
        CatalogGridItem(item, state.resolvingId == item.id) { viewModel.openDetails(item) }
      }
      if (state.isLoadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
    }
  }
  if (showSettings) CatalogSettingsDialog(viewModel) { showSettings = false }
  state.selectedItem?.let { item ->
    MediaDetailsDialog(
      item = item,
      streams = state.streamOptions,
      sourceFilter = state.sourceFilter,
      isLoading = state.resolvingId == item.id,
      onLoadSources = { viewModel.resolve(item, state.selectedSeason, state.selectedEpisode) },
      onEpisode = { season, episode -> viewModel.resolve(item, season, episode) },
      onFilter = viewModel::setSourceFilter,
      onSelect = { stream ->
        if (stream.isPlayable) {
          viewModel.playStream(stream)
        } else {
          context.startActivity(Intent(context, TorrentSelectionActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(stream.url)
            putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, stream.url)
            putExtra(MediaUtils.EXTRA_MEDIA_TITLE, item.title)
            putExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION, item.overview)
            putExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL, item.posterUrl)
            putExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL, item.backdropUrl)
            stream.torrentFileIndex?.let { putExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, it) }
          })
        }
      },
      onDismiss = viewModel::closeDetails,
    )
  }
}

@Composable
private fun CatalogHero(item: MediaItem) {
  Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(22.dp))) {
    AsyncImage(model = item.backdropUrl ?: item.posterUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color(0xFF090A0F)))))
    Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
      Text(item.provider.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      Text(item.title, style = MaterialTheme.typography.headlineSmall, color = androidx.compose.ui.graphics.Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(item.type.name, style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .75f))
    }
  }
}

@Composable
private fun CatalogStatusState(message: String, onRetry: () -> Unit, onEdit: (() -> Unit)? = null) {
  Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("◌", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
    Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onRetry) { Text("Retry") }
      onEdit?.let { TextButton(onClick = it) { Text("Edit settings") } }
    }
  }
}

@Composable
private fun CatalogSettingsDialog(viewModel: CatalogViewModel, onDismiss: () -> Unit) {
  val initial = remember { viewModel.currentSettings() }
  var tmdbKey by remember { mutableStateOf(initial.tmdbKey) }
  var resolverUrl by remember { mutableStateOf(initial.resolverUrl) }
  var resolverToken by remember { mutableStateOf(initial.resolverToken) }
  var resolverPath by remember { mutableStateOf(initial.resolverPath) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Catalog and resolver") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Keys and tokens are stored with Android encrypted preferences.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(tmdbKey, { tmdbKey = it }, label = { Text("TMDB API key") }, singleLine = true)
        OutlinedTextField(resolverUrl, { resolverUrl = it }, label = { Text("Resolver HTTPS base URL") }, singleLine = true)
        OutlinedTextField(resolverToken, { resolverToken = it }, label = { Text("Resolver bearer token") }, singleLine = true)
        OutlinedTextField(resolverPath, { resolverPath = it }, label = { Text("Stream endpoint path") }, singleLine = true)
        Text("Use {type}, {imdbId}, or {tmdbId}. Default: /stream/{type}/{imdbId}.json", style = MaterialTheme.typography.labelSmall)
      }
    },
    confirmButton = { Button(onClick = { viewModel.saveSettings(tmdbKey, resolverUrl, resolverToken, resolverPath); onDismiss() }) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun MediaDetailsDialog(
  item: MediaItem,
  streams: List<StreamOption>,
  sourceFilter: String,
  isLoading: Boolean,
  onLoadSources: () -> Unit,
  onEpisode: (Int, Int) -> Unit,
  onFilter: (String) -> Unit,
  onSelect: (StreamOption) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(item.title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item.overview.takeIf { it.isNotBlank() }?.let {
          Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        if (item.type == app.infinity.mpvz.catalog.MediaType.TV && item.seasons.isNotEmpty()) {
          Text("Seasons", style = MaterialTheme.typography.titleSmall)
          item.seasons.forEach { season ->
            Text("Season ${season.number}", style = MaterialTheme.typography.labelLarge)
            season.episodes.forEach { episode ->
              TextButton(onClick = { onEpisode(season.number, episode.number) }, modifier = Modifier.fillMaxWidth()) {
                Text("${episode.number}. ${episode.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        } else if (streams.isEmpty()) {
          Button(onClick = onLoadSources, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
            Text(if (isLoading) "Finding sources…" else "Find sources")
          }
        }
        Text("Sources (best quality first)", style = MaterialTheme.typography.titleSmall)
        listOf("All", "WatchHub", "Torrentio").forEach { filter ->
          TextButton(onClick = { onFilter(filter) }) { Text(if (filter == sourceFilter) "[$filter]" else filter) }
        }
        streams.filter { sourceFilter == "All" || (sourceFilter == "Torrentio" && !it.isPlayable) || (sourceFilter == "WatchHub" && it.isPlayable) }.forEach { stream ->
          val metadata = listOfNotNull(
            stream.qualityRank.takeIf { it > 0 }?.let { "${it}p" },
            stream.seeders.takeIf { it > 0 }?.let { "$it seeders" },
            stream.size,
            stream.source,
          ).joinToString(" • ")
          Button(onClick = { onSelect(stream) }, modifier = Modifier.fillMaxWidth()) {
            Text(
              listOf(stream.title, metadata, if (stream.isPlayable) "Play stream" else "Open torrent source").filter { it.isNotBlank() }.joinToString("\n"),
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        if (streams.isEmpty()) Text("No sources returned by the resolver.")
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
