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
object StreamScreen : app.infinity.mpvz.presentation.Screen {
  @Composable override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val viewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.Factory(context.applicationContext as android.app.Application))
    val state by viewModel.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
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
          actions = { IconButton(onClick = { showSettings = true }) { Icon(Icons.RoundedFilled.Settings, "Stream settings") } },
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search streams") },
            leadingIcon = { Icon(Icons.RoundedFilled.Search, "Search") },
            tonalElevation = 0.dp,
          )
        }
        state.items.firstOrNull()?.let { hero -> item { StreamHero(hero) { viewModel.openDetails(hero) } } }
        StreamRail("Trending Movies", state.items.filter { it.type.name == "MOVIE" && it.provider != CatalogProvider.KITSU }) { viewModel.openDetails(it) }
        StreamRail("Popular Series", state.items.filter { it.type.name == "TV" && it.provider != CatalogProvider.KITSU }) { viewModel.openDetails(it) }
        StreamRail("Top Anime", state.items.filter { it.provider == CatalogProvider.KITSU }) { viewModel.openDetails(it) }
        if (state.isLoading) item { Text("Loading streams…", modifier = Modifier.padding(16.dp)) }
        if (state.error != null) item { Text(state.error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
      }
    }
    if (showSettings) StreamResolverSettingsDialog(viewModel) { showSettings = false }
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
@Composable private fun StreamHero(item: MediaItem, onClick: () -> Unit) {
  androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
    AsyncImage(model = item.backdropUrl ?: item.posterUrl, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0D0F14)))))
    Text(item.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(16.dp).align(androidx.compose.ui.Alignment.BottomStart))
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
