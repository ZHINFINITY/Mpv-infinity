package app.infinity.mpvz.ui.browser.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewmodel.compose.viewModel
import app.infinity.mpvz.catalog.CatalogProvider
import app.infinity.mpvz.catalog.CatalogViewModel
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.ui.components.InlineSearchBar
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.preferences.PreferencesScreen
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
    val heroInitialPage = remember(heroItems) {
      if (heroItems.size > 1) {
        val middle = Int.MAX_VALUE / 2
        middle - (middle % heroItems.size)
      } else 0
    }
    val heroPagerState = rememberPagerState(initialPage = heroInitialPage, pageCount = { if (heroItems.size > 1) Int.MAX_VALUE else heroItems.size })
    LaunchedEffect(heroPagerState.settledPage, heroItems.size) {
      if (heroItems.size > 1) {
        delay(5000)
        if (!heroPagerState.isScrollInProgress) {
          heroPagerState.animateScrollToPage(heroPagerState.currentPage + 1, animationSpec = tween(800))
        }
      }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showCatalogs by rememberSaveable { mutableStateOf(false) }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var browseRail by rememberSaveable { mutableStateOf<String?>(null) }
    var genreFilter by rememberSaveable { mutableStateOf("All") }
    var searchFilter by rememberSaveable { mutableStateOf("All") }
    val isRefreshing = remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    val streamListState = rememberLazyListState()
    val searchActive = isSearching || state.query.isNotBlank()
    LaunchedEffect(browseRail) {
      androidx.compose.runtime.snapshotFlow {
        streamListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      }.collect { lastVisibleIndex ->
        val total = streamListState.layoutInfo.totalItemsCount
        if (browseRail != null && total > 0 && lastVisibleIndex >= total - 4) viewModel.loadMore()
      }
    }
    LaunchedEffect(searchActive, state.query, state.items.size, catalogSources) {
      Log.i("MpvCatalogDiag", "screen searchActive=$searchActive query=\"${state.query}\" items=${state.items.size} sources=${catalogSources.map { "${it.id}:${it.isEnabled}" }} browseRail=${browseRail ?: "<home>"}")
    }
    BackHandler(enabled = searchActive || browseRail != null) {
      if (browseRail != null) browseRail = null
      else {
        isSearching = false
        viewModel.setQuery("")
      }
    }
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
          title = {
            if (searchActive) {
              CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
                InlineSearchBar(
                  query = state.query,
                  onQueryChange = viewModel::setQuery,
                  onSearch = viewModel::setQuery,
                  modifier = Modifier.fillMaxWidth().height(48.dp),
                  inputFieldModifier = Modifier.height(48.dp),
                  placeholder = { Text("Search streams", style = MaterialTheme.typography.bodyMedium) },
                  leadingIcon = { Icon(Icons.RoundedFilled.Search, "Search", modifier = Modifier.size(20.dp)) },
                  tonalElevation = 0.dp,
                  windowInsets = WindowInsets(0.dp),
                )
              }
            } else Text(if (browseRail != null) "Browse" else "Stream")
          },
          navigationIcon = { IconButton(onClick = { backstack.popSafely() }) { Icon(Icons.RoundedFilled.ArrowBack, "Back") } },
          actions = {
            IconButton(onClick = {
              if (searchActive) {
                isSearching = false
                viewModel.setQuery("")
              } else {
                isSearching = true
              }
            }) { Icon(if (searchActive) Icons.RoundedFilled.Close else Icons.RoundedFilled.Search, if (searchActive) "Close search" else "Search") }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.RoundedFilled.Explore, "Catalogs and resolvers") }
            IconButton(onClick = { backstack.add(PreferencesScreen) }) { Icon(Icons.RoundedFilled.Settings, "General settings") }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
      },
    ) { padding ->
      PullRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
          if (!isRefreshing.value) refreshScope.launch {
            isRefreshing.value = true
            try { viewModel.refreshAll() } finally { isRefreshing.value = false }
          }
        },
        modifier = Modifier.fillMaxSize().padding(padding),
      ) {
        LazyColumn(
          state = streamListState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = 96.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        item { Spacer(Modifier.height(4.dp)) }
        if (searchActive) item {
          Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Movies", "TV Shows", "Episodes").forEach { filter ->
              val selected = searchFilter == filter
              FilterChip(
                selected = selected,
                onClick = { searchFilter = filter },
                label = { Text(filter, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
              )
            }
          }
        }
        if (state.isLoading && state.items.isEmpty()) item { StreamLoadingState(searching = searchActive) }
        if (!searchActive && state.query.isBlank() && browseRail == null && heroItems.isNotEmpty()) item { StreamHeroCarousel(heroItems, heroPagerState) { openResolverChooser(context, it) } }
        val sourceNames = catalogSources.associate { it.id to it.name }
        val rails = state.items.groupBy { item ->
          val title = item.catalogName ?: when (item.catalogSourceId) {
            "cinemeta-movies" -> "Trending Movies"
            "cinemeta-series" -> "Popular Series"
            "kitsu-anime" -> "Top Anime"
            else -> sourceNames[item.catalogSourceId] ?: item.provider.name
          }
          "${item.catalogSourceId.orEmpty()}|${item.catalogId.orEmpty()}|$title"
        }.mapValues { (_, sourceItems) -> sourceItems.distinctBy { "${it.catalogSourceId}:${it.catalogId}:${it.providerId ?: it.id}" } }
        if (!searchActive && state.query.isBlank() && browseRail == null) rails.forEach { (title, sourceItems) ->
          StreamRail(title.substringAfterLast('|'), sourceItems, onSeeMore = { browseRail = title; genreFilter = "All" }) { openResolverChooser(context, it) }
        }
        if (!searchActive && state.query.isBlank() && browseRail != null) {
          val browseItems = rails[browseRail].orEmpty()
          val genres = listOf("All") + browseItems.flatMap { it.genres }.distinct().sorted()
          item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              genres.forEach { genre -> FilterChip(selected = genreFilter == genre, onClick = { genreFilter = genre }, label = { Text(genre) }) }
            }
          }
          browseItems.filter { genreFilter == "All" || genreFilter in it.genres }.chunked(2).forEach { rowItems ->
            item {
              Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { mediaItem ->
                  androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { CatalogGridItem(mediaItem, false) { openResolverChooser(context, mediaItem) } }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
              }
            }
          }
        }
        if (searchActive && state.query.isNotBlank()) {
          val searchItems = state.items.filter { item ->
            when (searchFilter) {
              "Movies" -> item.type == MediaType.MOVIE
              "TV Shows" -> item.type == MediaType.TV
              "Episodes" -> item.type == MediaType.TV && item.seasons.any { season -> season.episodes.isNotEmpty() }
              else -> true
            }
          }
          if (searchItems.isEmpty() && !state.isLoading) item { Text("No results found for \"${state.query}\"", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
          searchItems.chunked(2).forEach { rowItems ->
            item {
              Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { mediaItem ->
                  androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { CatalogGridItem(mediaItem, false) { openResolverChooser(context, mediaItem) } }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
              }
            }
          }
        }
        if (state.isLoading && state.items.isNotEmpty()) item { StreamLoadingState(searching = searchActive) }
        if (state.isLoadingMore) item { StreamLoadingState(searching = false, compact = true) }
        if (state.error != null) item { Text(state.error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }
      }
    }
    if (showSettings) StreamResolverSettingsDialog(viewModel) { showSettings = false }

  }
}

private fun openResolverChooser(context: android.content.Context, item: MediaItem) {
  Log.i("MpvCatalogDiag", "item click title=\"${item.title}\" source=${item.catalogSourceId} catalog=${item.catalogId} type=${item.catalogType} providerId=${item.providerId}")
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

@Composable
private fun StreamLoadingState(searching: Boolean, compact: Boolean = false) {
  Box(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 12.dp else 40.dp),
    contentAlignment = androidx.compose.ui.Alignment.Center,
  ) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Icon(
        Icons.RoundedFilled.Movie,
        contentDescription = if (searching) "Searching" else "Loading catalog",
        modifier = Modifier.size(if (compact) 32.dp else 56.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
      if (!compact) {
        Text(
          if (searching) "Finding titles" else "Loading catalog",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

private fun LazyListScope.StreamRail(title: String, items: List<MediaItem>, onSeeMore: (() -> Unit)?, onClick: (MediaItem) -> Unit) {
  if (items.isEmpty()) return
  item {
    AnimatedVisibility(
      visible = true,
      enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 8 },
    ) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          Text("Popular and recently added", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        onSeeMore?.let { TextButton(onClick = it) { Text("See all") } }
      }
    }
  }
  item {
    AnimatedVisibility(
      visible = true,
      enter = fadeIn(tween(520)) + slideInVertically(tween(520)) { it / 10 },
    ) {
      LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items.take(18), key = { "stream-${it.catalogSourceId}-${it.catalogId}-${it.providerId ?: it.id}" }) { item ->
          androidx.compose.foundation.layout.Box(Modifier.width(144.dp)) { CatalogGridItem(item, false) { onClick(item) } }
        }
      }
    }
  }
}

@Composable private fun itemHeader(title: String) { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
@Composable private fun StreamHeroCarousel(items: List<MediaItem>, pagerState: androidx.compose.foundation.pager.PagerState, onClick: (MediaItem) -> Unit) {
  HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
    val item = items[page % items.size]
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(300.dp).clip(RoundedCornerShape(20.dp)).clickable { onClick(item) }) {
      AsyncImage(model = item.backdropUrl ?: item.posterUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = .96f)))))
      androidx.compose.foundation.layout.Column(Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
          androidx.compose.material3.Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .9f)) {
            Text(if (item.type == MediaType.TV) "SERIES" else "MOVIE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
          }
          item.releaseYear?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White) }
          item.contentRating?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .8f)) }
        }
        Text(item.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.overview.isNotBlank()) Text(item.overview, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .78f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Button(onClick = { onClick(item) }, shape = RoundedCornerShape(14.dp)) { Icon(Icons.RoundedFilled.PlayArrow, "Play"); Text("Watch / Details", modifier = Modifier.padding(start = 6.dp)) }
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
  val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    androidx.compose.foundation.layout.Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text("Stream resolvers", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      Text("Configure metadata and stream resolver sources", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
          androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text("Automatically choose best torrent", style = MaterialTheme.typography.titleMedium)
            Text("Use the highest-quality torrent when available.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          androidx.compose.material3.Switch(checked = autoChooseBest, onCheckedChange = { autoChooseBest = it })
        }
        endpoints.forEachIndexed { index, endpoint ->
          Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Switch(checked = endpoint.enabled, onCheckedChange = { checked -> endpoints = endpoints.toMutableList().also { it[index] = endpoint.copy(enabled = checked) } })
            Spacer(Modifier.width(16.dp))
            Text(endpoint.baseUrl, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { endpoints = endpoints.filterIndexed { i, _ -> i != index } }) { Icon(Icons.RoundedFilled.Delete, "Delete") }
          }
        }
        androidx.compose.material3.OutlinedTextField(
          value = newUrl,
          onValueChange = { newUrl = it },
          placeholder = { Text("Resolver base URL") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
        )
        androidx.compose.material3.Button(
          onClick = { if (newUrl.isNotBlank()) { viewModel.addResolverEndpoint(newUrl); endpoints = viewModel.resolvers.value; newUrl = "" } },
          shape = RoundedCornerShape(24.dp),
        ) { Text("Add resolver + catalog rails") }
      }
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Button(onClick = { viewModel.saveAutoChooseBestTorrent(autoChooseBest); viewModel.saveSettings(endpoints, initial.resolverToken, initial.resolverPath); onDismiss() }, shape = RoundedCornerShape(24.dp)) { Text("Save") }
      }
      Spacer(Modifier.height(8.dp))
    }
  }
}

@Composable private fun CatalogProvidersDialog(viewModel: CatalogViewModel, onDismiss: () -> Unit) {
  val initial = remember { viewModel.currentCatalogSources() }
  var sources by remember { mutableStateOf(initial) }
  var newUrl by remember { mutableStateOf("") }
  val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    androidx.compose.foundation.layout.Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Catalog providers", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      Text("Choose the metadata catalogs used on the Stream home", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sources.forEachIndexed { index, source ->
          Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Switch(checked = source.isEnabled, onCheckedChange = { enabled -> sources = sources.toMutableList().also { it[index] = source.copy(isEnabled = enabled) } })
            Spacer(Modifier.width(16.dp))
            Text(source.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { sources = sources.filterIndexed { i, _ -> i != index } }) { Icon(Icons.RoundedFilled.Delete, "Delete") }
          }
        }
        androidx.compose.material3.OutlinedTextField(
          value = newUrl,
          onValueChange = { newUrl = it },
          placeholder = { Text("Add catalog provider (API or endpoint URL)") },
          supportingText = { Text("Supports custom catalog endpoints and public metadata APIs.") },
          minLines = 2,
          maxLines = 2,
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
        )
        androidx.compose.material3.Button(onClick = {
          val manifestUrl = newUrl.trim().let { value ->
            if (value.endsWith("manifest.json", ignoreCase = true)) value else value.trimEnd('/') + "/manifest.json"
          }
          if (manifestUrl.startsWith("http://") || manifestUrl.startsWith("https://")) {
            val name = manifestUrl.substringAfter("://").substringBefore('/').ifBlank { "Custom catalog" }
            val source = app.infinity.mpvz.catalog.CatalogSource("custom-${manifestUrl.hashCode()}", name, manifestUrl)
            sources = (sources.filterNot { it.manifestUrl.equals(manifestUrl, ignoreCase = true) } + source)
            newUrl = ""
          }
        }, shape = RoundedCornerShape(24.dp)) { Text("Add catalog") }
      }
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Button(onClick = { viewModel.saveCatalogSources(sources); onDismiss() }, shape = RoundedCornerShape(24.dp)) { Text("Save") }
      }
      Spacer(Modifier.height(8.dp))
    }
  }
}