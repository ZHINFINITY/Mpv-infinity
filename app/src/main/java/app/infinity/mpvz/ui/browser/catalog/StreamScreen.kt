package app.infinity.mpvz.ui.browser.catalog

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.infinity.mpvz.catalog.CatalogSource
import app.infinity.mpvz.catalog.CatalogViewModel
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.catalog.StreamOption
import app.infinity.mpvz.presentation.Screen
import app.infinity.mpvz.presentation.components.pullrefresh.PullRefreshBox
import app.infinity.mpvz.ui.browser.components.BrowserTopBar
import app.infinity.mpvz.ui.components.InlineSearchBar
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.preferences.MediaServersPreferencesScreen
import app.infinity.mpvz.ui.utils.LocalBackStack
import app.infinity.mpvz.ui.utils.popSafely
import app.infinity.mpvz.utils.media.MediaUtils
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.serialization.Serializable

private const val HERO_AUTO_SCROLL_INTERVAL_MS = 8_000L

@Serializable
object StreamScreen : Screen {
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: CatalogViewModel = viewModel(
      key = "nuvio-stream-catalog",
      factory = CatalogViewModel.Factory(context.applicationContext as Application),
    )
    val state by viewModel.state.collectAsState()
    val catalogSources by viewModel.catalogSources.collectAsState()
    val playbackStream by viewModel.playbackStream.collectAsState()

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchFilter by rememberSaveable { mutableStateOf("All") }
    var browseRail by rememberSaveable { mutableStateOf<String?>(null) }
    var genreFilter by rememberSaveable { mutableStateOf("All") }
    val streamListState = rememberLazyListState()
    val refreshScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val isRefreshing = remember { mutableStateOf(false) }
    val searchActive = isSearching || state.query.isNotBlank()

    LaunchedEffect(lifecycleOwner, viewModel) {
      val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) viewModel.reloadAddonConfiguration()
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      viewModel.reloadAddonConfiguration()
      try {
        kotlinx.coroutines.awaitCancellation()
      } finally {
        lifecycleOwner.lifecycle.removeObserver(observer)
      }
    }

    LaunchedEffect(playbackStream) {
      val stream = playbackStream ?: return@LaunchedEffect
      if (stream.isExternal) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(stream.url)))
      } else if ((stream.url.startsWith("http://", ignoreCase = true) || stream.url.startsWith("https://", ignoreCase = true)) && stream.isPlayable) {
        context.startActivity(Intent(context, PlayerActivity::class.java).apply {
          action = Intent.ACTION_VIEW
          data = Uri.parse(stream.url)
          putExtra(Intent.EXTRA_STREAM, Uri.parse(stream.url))
          putExtra(MediaUtils.EXTRA_MEDIA_TITLE, state.selectedItem?.title ?: stream.title)
          putExtra("title", state.selectedItem?.title ?: stream.title)
          putExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION, state.selectedItem?.overview.orEmpty())
          putExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL, state.selectedItem?.posterUrl)
          putExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL, state.selectedItem?.backdropUrl)
          putExtra("is_audio", false)
          stream.filename?.let { putExtra("filename", it) }
          stream.mimeType?.let { putExtra("mime_type", it) }
          if (stream.headers.isNotEmpty()) {
            putExtra("headers", stream.headers.flatMap { (name, value) -> listOf(name, value) }.toTypedArray())
          }
        })
      }
      viewModel.closeDetails()
      viewModel.consumePlaybackStream()
    }

    val sourceNames = remember(catalogSources) { catalogSources.associate { it.id to it.name } }
    val rails = remember(state.items, sourceNames) {
      state.items.groupBy { item ->
        val title = item.catalogName ?: sourceNames[item.catalogSourceId] ?: "Discover"
        "${item.catalogSourceId.orEmpty()}|${item.catalogType.orEmpty()}|${item.catalogId.orEmpty()}|$title"
      }.mapValues { (_, items) ->
        items.distinctBy { "${it.catalogSourceId}:${it.catalogId}:${it.providerId ?: it.id}" }
      }
    }
    val heroItems = remember(state.items, state.query) {
      if (state.query.isNotBlank()) emptyList()
      else state.items.distinctBy { "${it.catalogSourceId}:${it.catalogId}:${it.providerId ?: it.id}" }.take(7)
    }

    LaunchedEffect(browseRail, state.items.size, state.query) {
      if (state.query.isNotBlank() || browseRail != null) return@LaunchedEffect
      androidx.compose.runtime.snapshotFlow { streamListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
        .collect { lastVisibleIndex ->
          val total = streamListState.layoutInfo.totalItemsCount
          if (total > 0 && lastVisibleIndex >= total - 4) viewModel.loadMore()
        }
    }

    BackHandler(enabled = searchActive || browseRail != null || state.selectedItem != null) {
      when {
        state.selectedItem != null -> viewModel.closeDetails()
        browseRail != null -> browseRail = null
        else -> {
          isSearching = false
          viewModel.setQuery("")
        }
      }
    }

    Column(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
      if (state.selectedItem != null) {
        CatalogDetailsPage(
          item = state.selectedItem!!,
          selectedSeason = state.selectedSeason,
          isLoading = state.resolvingId == state.selectedItem?.id,
          error = state.error,
          onBack = viewModel::closeDetails,
          onChooseSeason = viewModel::selectSeason,
          onFindMovieStreams = { viewModel.resolve(state.selectedItem!!) },
          onChooseEpisode = { season, episode -> viewModel.resolve(state.selectedItem!!, season, episode) },
        )
      } else {
        NuvioStreamTopOverlay(
          query = state.query,
          isSearching = searchActive,
          hasHero = false,
          title = if (browseRail == null) "Discover" else rails[browseRail]?.firstOrNull()?.catalogName ?: "Browse catalog",
          onQueryChange = viewModel::setQuery,
          onSearch = { isSearching = true },
          onCloseSearch = {
            keyboardController?.hide()
            isSearching = false
            viewModel.setQuery("")
          },
          onOpenSettings = { backstack.add(MediaServersPreferencesScreen) },
          onSubmitSearch = { keyboardController?.hide(); viewModel.setQuery(state.query) },
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
          PullRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshAll() },
            modifier = Modifier.fillMaxSize(),
          ) {
          LazyColumn(
          state = streamListState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(
            // Match Jellyfin's top shell: content always starts beneath the header layer.
            top = 12.dp,
            bottom = 104.dp,
          ),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (searchActive) item {
            Row(
              Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              listOf("All", "Movies", "TV Shows").forEach { filter ->
                FilterChip(
                  selected = searchFilter == filter,
                  onClick = { searchFilter = filter },
                  label = { Text(filter, fontWeight = if (searchFilter == filter) FontWeight.Bold else FontWeight.Normal) },
                )
              }
            }
          }

          if (!searchActive && browseRail == null && heroItems.isNotEmpty()) {
            item(key = "stream_hero") {
              NuvioStyleHero(items = heroItems, onItemClick = viewModel::showDetails)
            }
          }

          if (state.isLoading && state.items.isEmpty()) item { StreamLoadingState() }

          if (!searchActive && browseRail == null) {
            rails.forEach { (railKey, items) ->
              val catalogType = railKey.split('|').getOrNull(1).orEmpty()
              val suffix = when (catalogType) {
                "movie" -> " Movies"
                "series", "tv" -> " TV Shows"
                else -> ""
              }
              val railTitle = railKey.substringAfterLast('|') + suffix
              streamCatalogRail(
                title = railTitle,
                items = items,
                onSeeAll = { browseRail = railKey; genreFilter = "All" },
                onNearEnd = viewModel::loadMore,
                onItemClick = viewModel::showDetails,
              )
            }
          }

          if (browseRail != null && state.query.isBlank()) {
            val browseItems = rails[browseRail].orEmpty()
            val genres = listOf("All") + browseItems.flatMap { it.genres }.distinct().sorted()
            item(key = "stream_browse_genres") {
              Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                genres.forEach { genre ->
                  FilterChip(selected = genreFilter == genre, onClick = { genreFilter = genre }, label = { Text(genre) })
                }
              }
            }
            browseItems.filter { genreFilter == "All" || genreFilter in it.genres }.chunked(2).forEachIndexed { index, rowItems ->
              item(key = "stream_browse_${browseRail}_$index") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  rowItems.forEach { mediaItem ->
                    Box(Modifier.weight(1f)) { NuvioCatalogPosterCard(mediaItem) { viewModel.showDetails(mediaItem) } }
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
                else -> true
              }
            }
            if (searchItems.isEmpty() && !state.isLoading && state.error == null) item { StreamEmptySearchState(state.query) }
            searchItems.chunked(2).forEachIndexed { index, rowItems ->
              item(key = "stream_search_$index") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  rowItems.forEach { mediaItem ->
                    Box(Modifier.weight(1f)) { NuvioCatalogPosterCard(mediaItem) { viewModel.showDetails(mediaItem) } }
                  }
                  if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
              }
            }
          }

          if (state.isLoading && state.items.isNotEmpty()) item { StreamLoadingState(compact = true) }
          if (state.isLoadingMore) item { StreamLoadingState(compact = true) }
          state.error?.takeIf { state.selectedItem == null }?.let { error ->
            item {
              Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::retry) { Text("Retry") }
              }
            }
          }
          if (!searchActive && browseRail == null && !state.isLoading && state.items.isEmpty() && state.error == null) {
            item(key = "stream_no_catalogs") {
              StreamNoCatalogsState(
                hasCatalogAddons = catalogSources.any { it.isEnabled },
                onOpenSettings = { backstack.add(MediaServersPreferencesScreen) },
                onRefresh = {
                  if (!isRefreshing.value) refreshScope.launch {
                    isRefreshing.value = true
                    try { viewModel.refreshAll() } finally { isRefreshing.value = false }
                  }
                },
              )
            }
          }
        }

          }
          if (isRefreshing.value) {
            CircularProgressIndicator(
              Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(22.dp),
              strokeWidth = 2.dp,
            )
          }
        }
        if (state.selectedItem != null && state.streamOptions.isNotEmpty()) {
          StreamLinksBottomSheet(
            title = state.streamTitle ?: state.selectedItem!!.title,
            streams = state.streamOptions,
            onDismiss = viewModel::closeStreams,
            onPlay = viewModel::playStream,
          )
        }
      }
    }

  }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StreamLinksBottomSheet(
  title: String,
  streams: List<StreamOption>,
  onDismiss: () -> Unit,
  onPlay: (StreamOption) -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
  ) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Streams", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
      streams.forEach { stream ->
        Surface(Modifier.fillMaxWidth().clickable { onPlay(stream) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
          Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Text(stream.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
              val extra = listOfNotNull(stream.qualityRank.takeIf { it > 0 }?.let { "${it}p" }, stream.size, stream.source).joinToString(" · ")
              if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.RoundedFilled.PlayArrow, contentDescription = "Play stream", tint = MaterialTheme.colorScheme.primary)
          }
        }
      }
      Spacer(Modifier.height(8.dp))
    }
  }
}

@Composable
private fun NuvioStreamTopOverlay(
  modifier: Modifier = Modifier,
  title: String,
  query: String,
  isSearching: Boolean,
  hasHero: Boolean,
  onQueryChange: (String) -> Unit,
  onSearch: () -> Unit,
  onCloseSearch: () -> Unit,
  onOpenSettings: () -> Unit,
  onSubmitSearch: () -> Unit,
) {
  val keyboardController = LocalSoftwareKeyboardController.current
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(isSearching) {
    if (isSearching) {
      delay(100)
      runCatching { focusRequester.requestFocus() }
      keyboardController?.show()
    }
  }
  if (isSearching) {
    Row(
      modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      InlineSearchBar(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = { onSubmitSearch() },
        modifier = Modifier.weight(1f),
        inputFieldModifier = Modifier.focusRequester(focusRequester),
        placeholder = { Text("Search movies, shows, episodes...") },
        leadingIcon = { Icon(Icons.RoundedFilled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = {
          IconButton(onClick = onCloseSearch) {
            Icon(Icons.RoundedFilled.Close, contentDescription = "Close search")
          }
        },
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
      )
    }
  } else {
    BrowserTopBar(
      title = title,
      showTitle = true,
      isInSelectionMode = false,
      selectedCount = 0,
      totalCount = 0,
      onCancelSelection = {},
      onSearchClick = onSearch,
      onSettingsClick = onOpenSettings,
      modifier = modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun NuvioStyleHero(items: List<MediaItem>, onItemClick: (MediaItem) -> Unit) {
  if (items.isEmpty()) return
  val pagerState = key(items.size) {
    rememberPagerState(
      initialPage = if (items.size > 1) Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2) % items.size else 0,
      pageCount = { if (items.size > 1) Int.MAX_VALUE else 1 },
    )
  }
  val heroScope = rememberCoroutineScope()

  LaunchedEffect(pagerState.settledPage, items.size) {
    if (items.size > 1) {
      delay(HERO_AUTO_SCROLL_INTERVAL_MS)
      if (!pagerState.isScrollInProgress) pagerState.animateScrollToPage(pagerState.currentPage + 1)
    }
  }

  BoxWithConstraints(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)),
  ) {
    val isWide = maxWidth >= 600.dp
    val width = maxWidth.value
    val heroHeight = when {
      width >= 1200f -> (width * .42f).dp.coerceIn(360.dp, 440.dp)
      width >= 840f -> (width * .46f).dp.coerceIn(340.dp, 420.dp)
      width >= 600f -> (width * .58f).dp.coerceIn(320.dp, 380.dp)
      else -> 360.dp
    }
    Box(Modifier.fillMaxWidth().height(heroHeight)) {
      HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        val item = items[page % items.size]
        Box(Modifier.fillMaxSize()) {
          AsyncImage(
            model = item.backdropUrl ?: item.posterUrl,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
            alignment = if (isWide) Alignment.TopCenter else Alignment.Center,
            contentScale = ContentScale.Crop,
          )
          Box(
            Modifier.fillMaxSize().background(
              Brush.verticalGradient(
                colors = listOf(
                  MaterialTheme.colorScheme.background.copy(alpha = .02f),
                  MaterialTheme.colorScheme.background.copy(alpha = .12f),
                  MaterialTheme.colorScheme.background.copy(alpha = .34f),
                  MaterialTheme.colorScheme.background.copy(alpha = .98f),
                ),
              ),
            ),
          )
          Column(
            modifier = Modifier
              .align(if (isWide) Alignment.BottomStart else Alignment.BottomCenter)
              .fillMaxWidth(if (isWide) .76f else 1f)
              .padding(horizontal = if (isWide) 42.dp else 24.dp, vertical = 22.dp),
            horizontalAlignment = if (isWide) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
              HeroInfo(item.type.name.lowercase().replaceFirstChar(Char::uppercase))
              item.genres.firstOrNull()?.let { HeroInfo("•  $it") }
              item.releaseYear?.let { HeroInfo("•  $it") }
            }
            Text(
              item.title,
              style = MaterialTheme.typography.displaySmall,
              color = MaterialTheme.colorScheme.onBackground,
              fontWeight = FontWeight.Black,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              textAlign = if (isWide) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (item.overview.isNotBlank()) {
              Text(
                item.overview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .78f),
                maxLines = if (isWide) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isWide) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center,
              )
            }
            Surface(
              modifier = Modifier.clickable { onItemClick(item) },
              color = MaterialTheme.colorScheme.onBackground,
              contentColor = MaterialTheme.colorScheme.background,
              shape = RoundedCornerShape(40.dp),
            ) {
              Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("View details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
              }
            }
            if (items.size > 1) {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { index, _ ->
                  val active = index == pagerState.currentPage % items.size
                  Box(
                    Modifier
                      .size(width = if (active) 28.dp else 8.dp, height = 8.dp)
                      .clip(CircleShape)
                      .background(MaterialTheme.colorScheme.onBackground.copy(alpha = if (active) .92f else .38f))
                      .clickable {
                        val current = pagerState.currentPage
                        val base = current - current % items.size
                        heroScope.launch { pagerState.animateScrollToPage(base + index) }
                      },
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun HeroInfo(text: String) {
  Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun LazyListScope.streamCatalogRail(
  title: String,
  items: List<MediaItem>,
  onSeeAll: () -> Unit,
  onNearEnd: () -> Unit,
  onItemClick: (MediaItem) -> Unit,
) {
  if (items.isEmpty()) return
  item(key = "rail-header-$title") {
    AnimatedVisibility(visible = true, enter = fadeIn() + expandVertically()) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onSeeAll, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
          Text("See All", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
          Icon(Icons.RoundedFilled.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
      }
    }
  }
  item(key = "rail-row-$title") {
    val rowState = rememberLazyListState()
    LaunchedEffect(rowState, items.size) {
      androidx.compose.runtime.snapshotFlow { rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
        .collect { lastVisible -> if (items.isNotEmpty() && lastVisible >= items.lastIndex - 4) onNearEnd() }
    }
    AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically { it / 10 }) {
      LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(items, key = { "stream-${it.catalogSourceId}-${it.catalogId}-${it.providerId ?: it.id}" }) { item ->
          Box(Modifier.width(136.dp)) { NuvioCatalogPosterCard(item) { onItemClick(item) } }
        }
      }
    }
  }
}

@Composable
private fun StreamLoadingState(compact: Boolean = false) {
  Box(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 44.dp),
    contentAlignment = Alignment.Center,
  ) {
    if (compact) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
      CircularProgressIndicator()
      Text("Loading catalogs", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun StreamEmptySearchState(query: String) {
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 44.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(Icons.RoundedFilled.Search, contentDescription = null, modifier = Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
    Text("No results found for \"$query\"", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun StreamNoCatalogsState(hasCatalogAddons: Boolean, onOpenSettings: () -> Unit, onRefresh: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 38.dp),
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
  ) {
    Column(
      Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 30.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
      Icon(Icons.RoundedFilled.Movie, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
      Text(if (hasCatalogAddons) "Catalogs are not responding" else "Connect a discovery catalog", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
      Text(
        if (hasCatalogAddons) {
          "Your installed catalog add-ons returned no titles. Check the add-on's catalog resources or try another catalog source."
        } else {
          "Nuvio separates discovery catalogs from video providers. A JavaScript repository such as Yoru installs stream scrapers; it does not create home rails. Add a Stremio-compatible catalog add-on under Global Settings → Network → Media Servers → Stream Catalogs. A TMDB key is optional metadata enrichment, not a catalog source."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      )
      Button(onClick = onOpenSettings) { Text("Add or manage catalog add-ons") }
      TextButton(onClick = onOpenSettings) { Text("Manage Nuvio video providers") }
      TextButton(onClick = onRefresh) { Text("Refresh catalogs") }
    }
  }
}
