package app.infinity.mpvz.ui.browser.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.lifecycle.viewmodel.compose.viewModel
import app.infinity.mpvz.catalog.CatalogProvider
import app.infinity.mpvz.catalog.CatalogViewModel
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.ui.components.InlineSearchBar
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.popSafely
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
    var heroItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(state.items) { heroItems = state.items.shuffled().take(7) }
    val heroPagerState = rememberPagerState(pageCount = { heroItems.size })
    LaunchedEffect(heroItems.size) {
      if (heroItems.size > 1) {
        while (true) {
          delay(5500)
          if (!heroPagerState.isScrollInProgress) heroPagerState.animateScrollToPage((heroPagerState.currentPage + 1) % heroItems.size, animationSpec = tween(800))
        }
      }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showCatalogs by rememberSaveable { mutableStateOf(false) }
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
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
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
        if (heroItems.isNotEmpty()) item { StreamHeroCarousel(heroItems, heroPagerState) { viewModel.openDetails(it) } }
        if (catalogSources.any { it.id == "cinemeta-movies" && it.isEnabled }) StreamRail("Trending Movies", state.items.filter { it.type.name == "MOVIE" && it.provider != CatalogProvider.KITSU }) { viewModel.openDetails(it) }
        if (catalogSources.any { it.id == "cinemeta-series" && it.isEnabled }) StreamRail("Popular Series", state.items.filter { it.type.name == "TV" && it.provider != CatalogProvider.KITSU }) { viewModel.openDetails(it) }
        if (catalogSources.any { it.id == "kitsu-anime" && it.isEnabled }) StreamRail("Top Anime", state.items.filter { it.provider == CatalogProvider.KITSU }) { viewModel.openDetails(it) }
        if (state.isLoading) item { Text("Loading streams…", modifier = Modifier.padding(16.dp)) }
        if (state.error != null) item { Text(state.error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
      }
    }
    if (showSettings) StreamResolverSettingsDialog(viewModel) { showSettings = false }
    if (showCatalogs) CatalogProvidersDialog(viewModel) { showCatalogs = false }
    state.selectedItem?.let { item -> MediaDetailsSheet(item = item, streams = state.streamOptions, sourceFilter = state.sourceFilter, sourceSort = state.sourceSort, isLoading = state.resolvingId == item.id, onLoadSources = { viewModel.resolve(item, state.selectedSeason, state.selectedEpisode) }, onEpisode = { s, e -> viewModel.resolve(item, s, e) }, onFilter = viewModel::setSourceFilter, onSort = viewModel::setSourceSort, onSelect = { viewModel.playStream(it) }, onBack = viewModel::closeDetails) }
  }
}

private fun LazyListScope.StreamRail(title: String, items: List<MediaItem>, onClick: (MediaItem) -> Unit) {
  if (items.isEmpty()) return
  item { itemHeader(title) }
  item {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      items(items.take(18), key = { "stream-${it.provider}-${it.providerId ?: it.id}" }) { item ->
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
  val initial = remember { viewModel.currentSettings() }
  var endpoints by remember { mutableStateOf(initial.resolvers) }
  var newUrl by remember { mutableStateOf("") }
  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Stream resolvers") },
    text = {
      androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        endpoints.forEachIndexed { index, endpoint ->
          Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Switch(checked = endpoint.enabled, onCheckedChange = { checked -> endpoints = endpoints.toMutableList().also { it[index] = endpoint.copy(enabled = checked) } })
            Text(endpoint.baseUrl, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = { endpoints = endpoints.filterIndexed { i, _ -> i != index } }) { Icon(Icons.RoundedFilled.Delete, "Delete") }
          }
        }
        androidx.compose.material3.OutlinedTextField(newUrl, { newUrl = it }, label = { Text("Resolver base URL") }, singleLine = true)
        androidx.compose.material3.TextButton(onClick = { if (newUrl.isNotBlank()) { endpoints = endpoints + app.infinity.mpvz.catalog.ResolverEndpoint(newUrl.trim()); newUrl = "" } }) { Text("Add resolver") }
      }
    },
    confirmButton = { androidx.compose.material3.Button(onClick = { viewModel.saveSettings(endpoints, initial.resolverToken, initial.resolverPath); onDismiss() }) { Text("Save") } },
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
          Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Switch(checked = source.isEnabled, onCheckedChange = { enabled -> sources = sources.toMutableList().also { it[index] = source.copy(isEnabled = enabled) } })
            Text(source.name, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = { sources = sources.filterIndexed { i, _ -> i != index } }) { Icon(Icons.RoundedFilled.Delete, "Delete") }
          }
        }
        androidx.compose.material3.OutlinedTextField(newUrl, { newUrl = it }, label = { Text("Stremio manifest URL") }, singleLine = true)
        androidx.compose.material3.TextButton(onClick = { if (newUrl.isNotBlank()) { sources = sources + app.infinity.mpvz.catalog.CatalogSource("custom-${newUrl.hashCode()}", "Custom catalog", newUrl.trim()); newUrl = "" } }) { Text("Add catalog") }
      }
    },
    confirmButton = { androidx.compose.material3.Button(onClick = { viewModel.saveCatalogSources(sources); onDismiss() }) { Text("Save") } },
    dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
