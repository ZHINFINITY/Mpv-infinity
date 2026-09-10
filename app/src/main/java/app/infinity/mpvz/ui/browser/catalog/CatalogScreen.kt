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
  val catalogSources by viewModel.catalogSources.collectAsState()
  fun launchTorrent(request: app.infinity.mpvz.catalog.TorrentLaunchRequest) {
    val item = request.item
    val episode = request.season?.let { season -> request.episode?.let { number -> item.seasons.firstOrNull { it.number == season }?.episodes?.firstOrNull { it.number == number } } }
    context.startActivity(Intent(context, TorrentSelectionActivity::class.java).apply {
      action = Intent.ACTION_VIEW
      data = Uri.parse(request.stream.url)
      putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, request.stream.url)
      putExtra(MediaUtils.EXTRA_MEDIA_TITLE, item.title)
      putExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION, item.overview)
      putExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL, item.posterUrl)
      putExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL, item.backdropUrl)
      putExtra("catalog_provider_id", item.providerId)
      putExtra("catalog_imdb_id", item.imdbId)
      putExtra("catalog_release_year", item.releaseYear)
      putExtra("catalog_rating", item.contentRating)
      putExtra("catalog_duration", item.duration)
      putExtra("catalog_genres", item.genres.joinToString(" • "))
      putExtra("is_series", item.type == app.infinity.mpvz.catalog.MediaType.TV)
      putExtra("streams_json", kotlinx.serialization.json.Json.encodeToString(request.streams))
      if (item.seasons.isNotEmpty()) putExtra("seasons_json", kotlinx.serialization.json.Json.encodeToString(item.seasons))
      episode?.let {
        putExtra("episode_season", request.season)
        putExtra("episode_number", request.episode)
        putExtra("episode_title", it.title)
        putExtra("episode_overview", it.overview)
        putExtra("episode_thumbnail", it.stillUrl)
      }
      request.stream.torrentFileIndex?.let { putExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, it) }
    })
  }
  LaunchedEffect(Unit) { viewModel.torrentLaunch.collect { launchTorrent(it) } }
  var showSettings by remember { mutableStateOf(false) }
  var searchOpen by remember { mutableStateOf(state.query.isNotBlank()) }
  var browseRail by remember { mutableStateOf<String?>(null) }
  var genreFilter by remember { mutableStateOf("All") }
  val featured = state.items.firstOrNull()
  val rails = remember(state.items, catalogSources) {
    val sourceNames = catalogSources.associate { it.id to it.name }
    state.items.groupBy { item ->
      item.catalogName ?: when (item.catalogSourceId) {
        "kitsu-anime" -> "Top Anime"
        "cinemeta-series" -> "Popular Series"
        "cinemeta-movies" -> "Trending Movies"
        else -> sourceNames[item.catalogSourceId] ?: item.provider.name
      }
    }.mapValues { (_, items) -> items.distinctBy { "${it.catalogSourceId}:${it.catalogId}:${it.providerId ?: it.id}" } }
  }
  val railItems = rails.values.flatten().toSet()

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

  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 96.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
      if (searchOpen) InlineSearchBar(
          query = state.query,
          onQueryChange = viewModel::setQuery,
          onSearch = viewModel::setQuery,
          modifier = Modifier.weight(1f),
          placeholder = { Text("Search movies and TV") },
          leadingIcon = { Icon(Icons.RoundedFilled.Search, "Search") },
          shape = RoundedCornerShape(24.dp),
          tonalElevation = 0.dp,
        ) else Spacer(Modifier.weight(1f))
      IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) viewModel.setQuery("") }) { Icon(Icons.RoundedFilled.Search, "Search") }
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
    if (state.query.isBlank() && browseRail == null) {
      rails.forEach { (railTitle, items) ->
        if (items.isNotEmpty()) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text(railTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { browseRail = railTitle; genreFilter = "All" }) { Text("See more") }
          }
          LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(items.take(12), key = { "rail-${it.catalogSourceId}-${it.catalogId}-${it.providerId ?: it.id}" }) { item ->
              Box(Modifier.width(130.dp)) { CatalogGridItem(item, state.resolvingId == item.id) { viewModel.openDetails(item) } }
            }
          }
        }
      }
    } else if (browseRail != null && state.query.isBlank()) {
      val browseItems = rails[browseRail ?: ""].orEmpty()
      val genres = listOf("All") + browseItems.flatMap { it.genres }.distinct().sorted()
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        genres.forEach { genre -> FilterChip(selected = genreFilter == genre, onClick = { genreFilter = genre }, label = { Text(genre) }) }
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
      val visibleItems = if (browseRail != null && state.query.isBlank()) rails[browseRail ?: ""].orEmpty().filter { genreFilter == "All" || genreFilter in it.genres } else state.items.filterNot { it in railItems }
      itemsIndexed(visibleItems, key = { _, item -> "${item.catalogSourceId}-${item.catalogId}-${item.providerId ?: item.id}" }) { index, item ->
        if (browseRail == null && index >= visibleItems.size - 3) viewModel.loadMore()
        CatalogGridItem(item, state.resolvingId == item.id) { viewModel.openDetails(item) }
      }
      if (state.isLoadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
    }
  }
  if (showSettings) CatalogSettingsDialog(viewModel, catalogSources) { showSettings = false }
}

@Composable
fun CatalogHero(item: MediaItem) {
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

@Composable@Composable
private fun CatalogSettingsDialog(
  viewModel: CatalogViewModel,
  currentSources: List<app.infinity.mpvz.catalog.CatalogSource>,
  onDismiss: () -> Unit,
) {
  var sources by remember { mutableStateOf(currentSources) }
  var newUrl by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    shape = RoundedCornerShape(28.dp),
    title = { Text("Catalog providers", style = MaterialTheme.typography.headlineSmall) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Choose the metadata catalogs used on the Stream home")
        sources.forEachIndexed { index, source ->
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Switch(
              checked = source.isEnabled,
              onCheckedChange = { enabled ->
                sources = sources.toMutableList().also { it[index] = source.copy(isEnabled = enabled) }
              },
            )
            Text(source.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { sources = sources.filterIndexed { i, _ -> i != index } }) {
              Icon(Icons.RoundedFilled.Delete, "Delete catalog provider")
            }
          }
        }
        OutlinedTextField(
          value = newUrl,
          onValueChange = { newUrl = it },
          placeholder = { Text("Add catalog provider (API or endpoint URL)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
        )
        Text("Supports custom catalog endpoints and public metadata APIs", style = MaterialTheme.typography.bodySmall)
        Button(
          onClick = {
            val base = newUrl.trim().removeSuffix("/manifest.json").trimEnd(/)
            if (base.isNotBlank()) {
              val manifestUrl = "$base/manifest.json"
              val name = base.substringAfter("://").substringBefore(/).ifBlank { "Catalog provider" }
              sources = (sources + app.infinity.mpvz.catalog.CatalogSource("resolver-${manifestUrl.hashCode()}", name, manifestUrl)).distinctBy { it.manifestUrl.lowercase() }
              newUrl = ""
            }
          },
          shape = RoundedCornerShape(24.dp),
        ) { Text("Add catalog") }
      }
    },
    confirmButton = {
      Button(onClick = { viewModel.saveCatalogSources(sources); onDismiss() }, shape = RoundedCornerShape(24.dp)) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
