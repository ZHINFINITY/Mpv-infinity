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
import androidx.compose.foundation.layout.size
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

@Composable
fun CatalogScreen() {
  val context = LocalContext.current
  val viewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.Factory(context.applicationContext as android.app.Application))
  val state by viewModel.state.collectAsState()
  var showSettings by remember { mutableStateOf(false) }

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
    CatalogDetailsPage(
      item = item,
      streams = state.streamOptions,
      sourceFilter = state.sourceFilter,
      isLoading = state.resolvingId == item.id,
      onLoadSources = { viewModel.resolve(item, state.selectedSeason, state.selectedEpisode) },
      onEpisode = { season, episode -> viewModel.resolve(item, season, episode) },
      onFilter = viewModel::setSourceFilter,
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
    return
  }

  Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
      OutlinedTextField(
        value = state.query,
        onValueChange = viewModel::setQuery,
        modifier = Modifier.weight(1f),
        singleLine = true,
        label = { Text("Search movies and TV") },
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
            CatalogProvider.MYANIMELIST -> "MyAnimeList"
            CatalogProvider.ANILIST -> "AniList"
          }) },
        )
      }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
    if (state.isLoading) Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    LazyVerticalGrid(
      columns = GridCells.Adaptive(130.dp),
      contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      itemsIndexed(state.items, key = { _, item -> "${item.provider}-${item.providerId ?: item.id}" }) { index, item ->
        if (index >= state.items.size - 4) viewModel.loadMore()
        CatalogCard(item, state.resolvingId == item.id) { viewModel.openDetails(item) }
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
private fun CatalogCard(item: MediaItem, resolving: Boolean, onClick: () -> Unit) {
  Card(Modifier.fillMaxWidth().clickable(enabled = !resolving, onClick = onClick)) {
    Column {
      AsyncImage(
        model = item.posterUrl,
        contentDescription = item.title,
        modifier = Modifier.fillMaxWidth().height(190.dp),
        contentScale = ContentScale.Crop,
      )
      Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
      Text(if (resolving) "Resolving…" else item.type.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 0.dp))
      Spacer(Modifier.height(8.dp))
    }
  }
}

@Composable
private fun CatalogDetailsPage(
  item: MediaItem,
  streams: List<StreamOption>,
  sourceFilter: String,
  isLoading: Boolean,
  onLoadSources: () -> Unit,
  onEpisode: (Int, Int) -> Unit,
  onFilter: (String) -> Unit,
  onSelect: (StreamOption) -> Unit,
  onBack: () -> Unit,
) {
  var activeSeason by remember(item.id) { mutableStateOf(item.seasons.firstOrNull()?.number) }
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
      TextButton(onClick = onBack) { Text("‹ Back") }
      Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    AsyncImage(model = item.backdropUrl ?: item.posterUrl, contentDescription = item.title, modifier = Modifier.fillMaxWidth().height(230.dp), contentScale = ContentScale.Crop)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(item.title, style = MaterialTheme.typography.headlineMedium)
      Text(item.provider.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
      if (item.overview.isNotBlank()) Text(item.overview, style = MaterialTheme.typography.bodyLarge)
      if (item.type == app.infinity.mpvz.catalog.MediaType.TV && item.seasons.isNotEmpty()) {
        Text("Seasons and episodes", style = MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(item.seasons) { season ->
            FilterChip(selected = activeSeason == season.number, onClick = { activeSeason = season.number }, label = { Text("Season ${season.number}") })
          }
        }
        item.seasons.firstOrNull { it.number == activeSeason }?.let { season ->
          season.episodes.forEach { episode ->
            Card(Modifier.fillMaxWidth().clickable { onEpisode(season.number, episode.number) }) {
              Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = episode.stillUrl, contentDescription = episode.title, modifier = Modifier.size(110.dp, 62.dp), contentScale = ContentScale.Crop)
                Column(Modifier.padding(start = 10.dp)) {
                  Text("${episode.number}. ${episode.title}", style = MaterialTheme.typography.titleSmall)
                  Text(episode.overview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
              }
            }
          }
        }
      } else if (streams.isEmpty()) {
        Button(onClick = onLoadSources, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) { Text(if (isLoading) "Finding sources…" else "Find sources") }
      }
      Text("Sources", style = MaterialTheme.typography.titleLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("All", "WatchHub", "Torrentio").forEach { filter -> TextButton(onClick = { onFilter(filter) }) { Text(if (filter == sourceFilter) "● $filter" else filter) } }
      }
      val visibleStreams = streams.filter { sourceFilter == "All" || (sourceFilter == "Torrentio" && !it.isPlayable) || (sourceFilter == "WatchHub" && it.isPlayable) }
      visibleStreams.forEach { stream ->
        val metadata = listOfNotNull(stream.qualityRank.takeIf { it > 0 }?.let { "${it}p" }, stream.seeders.takeIf { it > 0 }?.let { "$it seeders" }, stream.size, stream.source).joinToString(" • ")
        Card(Modifier.fillMaxWidth().clickable { onSelect(stream) }) {
          Column(Modifier.padding(14.dp)) {
            Text(stream.title, style = MaterialTheme.typography.titleMedium)
            if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(if (stream.isPlayable) "Play source" else "Stream torrent", style = MaterialTheme.typography.labelLarge)
          }
        }
      }
      if (streams.isEmpty() && !isLoading && item.type == app.infinity.mpvz.catalog.MediaType.TV) Text("Select an episode to load sources.", style = MaterialTheme.typography.bodyMedium)
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
