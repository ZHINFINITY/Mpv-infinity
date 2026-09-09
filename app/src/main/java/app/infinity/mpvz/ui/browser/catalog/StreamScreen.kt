package app.infinity.mpvz.ui.browser.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewmodel.compose.viewModel
import app.infinity.mpvz.catalog.CatalogProvider
import app.infinity.mpvz.catalog.CatalogViewModel
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.ui.components.InlineSearchBar
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.popSafely
import app.infinity.mpvz.ui.torrent.TorrentSelectionActivity
import app.infinity.mpvz.utils.media.MediaUtils
import app.infinity.mpvz.presentation.components.pullrefresh.PullRefreshBox
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@kotlinx.serialization.Serializable
object StreamScreen : app.infinity.mpvz.presentation.Screen {
  @Composable override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val viewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.Factory(context.applicationContext as android.app.Application))
    val state by viewModel.state.collectAsState()
    val catalogSources by viewModel.catalogSources.collectAsState()
    fun openTorrent(item: MediaItem, stream: app.infinity.mpvz.catalog.StreamOption, streams: List<app.infinity.mpvz.catalog.StreamOption> = listOf(stream), season: Int? = state.selectedSeason, episodeNumber: Int? = state.selectedEpisode) {
      val selectedEpisode = season?.let { seasonNumber ->
        episodeNumber?.let { number -> item.seasons.firstOrNull { it.number == seasonNumber }?.episodes?.firstOrNull { it.number == number } }
      }
      context.startActivity(Intent(context, TorrentSelectionActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(stream.url)
        putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, stream.url)
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
        putExtra("streams_json", kotlinx.serialization.json.Json.encodeToString(streams))
        if (item.seasons.isNotEmpty()) putExtra("seasons_json", kotlinx.serialization.json.Json.encodeToString(item.seasons))
        selectedEpisode?.let { episode ->
          putExtra("episode_season", season)
          putExtra("episode_number", episodeNumber)
          putExtra("episode_title", episode.title)
          putExtra("episode_overview", episode.overview)
          putExtra("episode_thumbnail", episode.stillUrl)
        }
        stream.torrentFileIndex?.let { putExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, it) }
      })
    }
    LaunchedEffect(Unit) {
      viewModel.torrentLaunch.collect { request -> openTorrent(request.item, request.stream, request.streams, request.season, request.episode) }
    }
    var heroItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(state.items) { heroItems = state.items.shuffled().take(7) }
    val heroPagerState = rememberPagerState(pageCount = { heroItems.size })
    LaunchedEffect(heroItems.size) {
      if (heroItems.size > 1) {
        while (true) {
          delay(5500)
          val pageCount = heroItems.size
          if (pageCount > 1 && !heroPagerState.isScrollInProgress) {
            heroPagerState.animateScrollToPage((heroPagerState.currentPage + 1) % pageCount, animationSpec = tween(800))
          }
        }
      }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showCatalogs by rememberSaveable { mutableStateOf(false) }
    val isRefreshing = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
      viewModel.resolvedUrl.collect { url ->
        if (url != null) {
          context.startActivity(android.content.Intent(context, app.infinity.mpvz.ui.player.PlayerActivity::class.java).apply { action = android.content.Intent.ACTION_VIEW; data = android.net.Uri.parse(url) })
          viewModel.consumeResolvedUrl()
        }
      }
    }
    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text("Stream") },
          navigationIcon = { IconButton(onClick = { backstack.popSafely() }) { Icon(Icons.RoundedFilled.ArrowBack, "Back") } },
          actions = {
            IconButton(onClick = { showCatalogs = true }) { Icon(Icons.RoundedFilled.Explore, "Catalogs") }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.RoundedFilled.Settings, "Resolvers") }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
      },
    ) { padding ->
      PullRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refreshAll() },
        modifier = Modifier.fillMaxSize().padding(padding),
      ) {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = 96.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        item {
          InlineSearchBar(
            query = state.query,
            onQueryChange = viewModel::setQuery,
            onSearch = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search streams") },
            leadingIcon = { Icon(Icons.RoundedFilled.Search, "Search") },
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0.dp),
          )
        }
        if (heroItems.isNotEmpty()) item { StreamHeroCarousel(heroItems, heroPagerState) { openResolverChooser(context, it) } }
        val sourceNames = catalogSources.associate { it.id to it.name }
        state.items.groupBy { item ->
          item.catalogName ?: when (item.catalogSourceId) {
            "cinemeta-movies" -> "Trending Movies"
            "cinemeta-series" -> "Popular Series"
            "kitsu-anime" -> "Top Anime"
            else -> sourceNames[item.catalogSourceId] ?: item.provider.name
          }
        }.forEach { (title, sourceItems) ->
          StreamRail(title, sourceItems.distinctBy { "${it.catalogSourceId}:${it.catalogId}:${it.providerId ?: it.id}" }) { openResolverChooser(context, it) }
        }
        if (state.isLoading) item { Text("Loading streams…", modifier = Modifier.padding(16.dp)) }
        if (state.error != null) item { Text(state.error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }
      }
    }
    if (showSettings) StreamResolverSettingsDialog(viewModel) { showSettings = false }
    if (showCatalogs) CatalogProvidersDialog(viewModel) { showCatalogs = false }
  }
}

private fun openResolverChooser(context: android.content.Context, item: MediaItem) {
  context.startActivity(Intent(context, TorrentSelectionActivity::class.java).apply {
    action = Intent.ACTION_VIEW
    putExtra(MediaUtils.EXTRA_MEDIA_TITLE, item.title)
    putExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION, item.overview)
    putExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL, item.posterUrl)
    putExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL, item.backdropUrl)
    putExtra("catalog_provider_id", item.providerId)
    putExtra("catalog_imdb_id", item.imdbId)
    putExtra("catalog_id", item.id)
    putExtra("catalog_provider", item.provider.name)
    putExtra("catalog_source_id", item.catalogSourceId)
    putExtra("catalog_type_name", item.catalogType)
    putExtra("catalog_type", item.type.name)
    putExtra("catalog_release_year", item.releaseYear)
    putExtra("catalog_rating", item.contentRating)
    putExtra("catalog_duration", item.duration)
    putExtra("catalog_genres", item.genres.joinToString(" • "))
    putExtra("is_series", item.type == MediaType.TV)
    putExtra("seasons_json", kotlinx.serialization.json.Json.encodeToString(item.seasons))
  })
}

private fun LazyListScope.StreamRail(title: String, items: List<MediaItem>, onClick: (MediaItem) -> Unit) {
  if (items.isEmpty()) return
  item { itemHeader(title) }
  item {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      items(items.take(18), key = { "stream-${it.catalogSourceId}-${it.catalogId}-${it.providerId ?: it.id}" }) { item ->
        androidx.compose.foundation.layout.Box(Modifier.width(130.dp)) { CatalogGridItem(item, false) { onClick(item) } }
      }
    }
  }
}

@Composable private fun itemHeader(title: String) { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
@Composable private fun StreamHeroCarousel(items: List<MediaItem>, pagerState: androidx.compose.foundation.pager.PagerState, onClick: (MediaItem) -> Unit) {
  HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
    val item = items[page]
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).clickable { onClick(item) }) {
      AsyncImage(model = item.backdropUrl ?: item.posterUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .8f), MaterialTheme.colorScheme.background))))
      androidx.compose.foundation.layout.Column(Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.AssistChip(onClick = { onClick(item) }, label = { Text("${item.type.name.lowercase().replaceFirstChar { it.uppercase() }}${item.releaseYear?.let { " • $it" }.orEmpty()}") })
        Text(item.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, maxLines = 2)
        androidx.compose.material3.Button(onClick = { onClick(item) }) { Icon(Icons.RoundedFilled.PlayArrow, "Details"); Text("Watch / Details", modifier = Modifier.padding(start = 6.dp)) }
      }
    }
  }
}

@Composable private fun StreamResolverSettingsDialog(viewModel: CatalogViewModel, onDismiss: () -> Unit) {
  val persistedResolvers by viewModel.resolvers.collectAsState()
  val initial = remember { viewModel.currentSettings() }
  var endpoints by remember(persistedResolvers) { mutableStateOf(persistedResolvers) }
  var newUrl by remember { mutableStateOf("") }
  var autoChooseBest by remember { mutableStateOf(viewModel.autoChooseBestTorrent) }
  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Stream resolvers") },
    text = {
      androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
          androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text("Automatically choose best torrent")
            Text("Use the highest-quality torrent when available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          androidx.compose.material3.Switch(checked = autoChooseBest, onCheckedChange = { autoChooseBest = it })
        }
        endpoints.forEachIndexed { index, endpoint ->
          Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Switch(checked = endpoint.enabled, onCheckedChange = { checked -> endpoints = endpoints.toMutableList().also { it[index] = endpoint.copy(enabled = checked) } })
            Spacer(Modifier.width(16.dp))
            Text(endpoint.baseUrl, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = { endpoints = endpoints.filterIndexed { i, _ -> i != index } }) { Icon(Icons.RoundedFilled.Delete, "Delete") }
          }
        }
        androidx.compose.material3.OutlinedTextField(newUrl, { newUrl = it }, label = { Text("Resolver base URL") }, singleLine = true)
        androidx.compose.material3.TextButton(onClick = { if (newUrl.isNotBlank()) { val updated = endpoints + app.infinity.mpvz.catalog.ResolverEndpoint(newUrl.trim()); viewModel.saveResolvers(updated); endpoints = updated; newUrl = "" } }) { Text("Add resolver") }
      }
    },
    confirmButton = { androidx.compose.material3.Button(onClick = { viewModel.saveAutoChooseBestTorrent(autoChooseBest); viewModel.saveSettings(endpoints, initial.resolverToken, initial.resolverPath); onDismiss() }) { Text("Save") } },
    dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable private fun CatalogProvidersDialog(viewModel: CatalogViewModel, onDismiss: () -> Unit) {
  val initial = remember { viewModel.currentCatalogSources() }
  var sources by remember { mutableStateOf(initial) }
  var newUrl by remember { mutableStateOf("") }
  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Catalog providers") },
    text = {
      androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEachIndexed { index, source ->
          Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Switch(checked = source.isEnabled, onCheckedChange = { enabled -> sources = sources.toMutableList().also { it[index] = source.copy(isEnabled = enabled) } })
            Spacer(Modifier.width(16.dp))
            Text(source.name, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = { sources = sources.filterIndexed { i, _ -> i != index } }) { Icon(Icons.RoundedFilled.Delete, "Delete") }
          }
        }
        androidx.compose.material3.OutlinedTextField(newUrl, { newUrl = it }, label = { Text("Add catalog provider (API or endpoint URL)") }, supportingText = { Text("Supports custom catalog endpoints and public metadata APIs.") }, singleLine = true)
        androidx.compose.material3.TextButton(onClick = {
          val manifestUrl = newUrl.trim().let { value ->
            if (value.endsWith("manifest.json", ignoreCase = true)) value else value.trimEnd('/') + "/manifest.json"
          }
          if (manifestUrl.startsWith("http://") || manifestUrl.startsWith("https://")) {
            val name = manifestUrl.substringAfter("://").substringBefore('/').ifBlank { "Custom catalog" }
            val source = app.infinity.mpvz.catalog.CatalogSource("custom-${manifestUrl.hashCode()}", name, manifestUrl)
            sources = (sources.filterNot { it.manifestUrl.equals(manifestUrl, ignoreCase = true) } + source)
            newUrl = ""
          }
        }) { Text("Add catalog") }
      }
    },
    confirmButton = { androidx.compose.material3.Button(onClick = { viewModel.saveCatalogSources(sources); onDismiss() }) { Text("Save") } },
    dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
