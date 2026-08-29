package app.infinity.mpvz.ui.browser.jellyfin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.presentation.components.RemoteImage
import app.infinity.mpvz.presentation.components.pullrefresh.PullRefreshBox
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeerrNativeDashboard(
  state: SeerrDiscoverState,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onSearch: (String) -> Unit,
  onOpenDetails: (SeerrMediaItem) -> Unit,
  onPlay: (SeerrMediaItem) -> Unit,
  onRequest: (SeerrMediaItem, Boolean, List<Int>?, SeerrAudioPreference) -> Unit,
  onDisconnect: () -> Unit,
) {
  var searchOpen by remember { mutableStateOf(false) }
  var searchText by remember { mutableStateOf("") }
  var selected by remember { mutableStateOf<SeerrMediaItem?>(null) }
  var profileOpen by remember { mutableStateOf(false) }
  val isRefreshing = remember { mutableStateOf(false) }
  BackHandler(onBack = onBack)
  val sections = if (searchOpen && state.searchQuery.isNotBlank()) listOf("Search results" to state.searchResults) else listOf("Trending" to state.trending, "Movies" to state.movies, "TV Shows" to state.shows)

  Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    TopAppBar(
      title = { Text("Discover", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.RoundedFilled.ArrowBack, "Back") } },
      actions = {
        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) { searchText = ""; onSearch("") } }) { Icon(if (searchOpen) Icons.RoundedFilled.Close else Icons.RoundedFilled.Search, "Search") }
        Box {
          IconButton(onClick = { profileOpen = true }) {
            Surface(Modifier.size(30.dp), CircleShape, MaterialTheme.colorScheme.primaryContainer) { Box(contentAlignment = Alignment.Center) { Icon(Icons.RoundedFilled.Person, "Profile", tint = MaterialTheme.colorScheme.onPrimaryContainer) } }
          }
          DropdownMenu(expanded = profileOpen, onDismissRequest = { profileOpen = false }) {
            DropdownMenuItem(text = { Text(state.userName ?: "Seerr account") }, onClick = { })
            DropdownMenuItem(text = { Text("Refresh") }, leadingIcon = { Icon(Icons.RoundedFilled.Refresh, null) }, onClick = { profileOpen = false; onRefresh() })
            DropdownMenuItem(text = { Text("Disconnect") }, leadingIcon = { Icon(Icons.RoundedFilled.LinkOff, null) }, onClick = { profileOpen = false; onDisconnect() })
          }
        }
      },
      colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
    AnimatedVisibility(visible = searchOpen, enter = fadeIn(), exit = fadeOut()) {
      OutlinedTextField(
        value = searchText,
        onValueChange = { searchText = it; if (it.length >= 2) onSearch(it) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        placeholder = { Text("Search movies and TV shows") },
        leadingIcon = { Icon(Icons.RoundedFilled.Search, null) },
      )
    }
    PullRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
    if (state.isLoading && state.movies.isEmpty() && state.shows.isEmpty()) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (state.error != null && sections.all { it.second.isEmpty() }) {
      Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(state.error, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRefresh) { Text("Retry") }
      }
    } else if (searchOpen && state.searchQuery.isNotBlank()) {
      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        item(span = { GridItemSpan(2) }) { SeerrNativeSection("Search results", "Results from Seerr") }
        gridItems(state.searchResults, key = { "search-${it.mediaType}-${it.id}" }) { item -> SeerrNativeCard(item) { selected = item; onOpenDetails(item) } }
        if (state.isSearching) item(span = { GridItemSpan(2) }) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
      }
    } else {
      LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        sections.forEach { (title, items) ->
          if (items.isNotEmpty()) {
            item { SeerrNativeSection(title, if (title == "Search results") "Results from Seerr" else "Browse and request media") }
            item { SeerrNativeRail(items) { selected = it; onOpenDetails(it) } }
          }
        }
      }
    }
    }
  }

  selected?.let { media ->
    val detailKey = "${media.mediaType}:${media.id}"
    val detailedMedia = state.details[detailKey] ?: media
    var is4k by remember(media.id) { mutableStateOf(false) }
    var selectedSeasons by remember(detailedMedia.id, detailedMedia.seasons) { mutableStateOf(detailedMedia.seasons.filterNot { it.available || it.requested }.map { it.seasonNumber }.toSet()) }
    var audioPreference by remember(detailedMedia.id) { mutableStateOf(SeerrAudioPreference.DEFAULT) }
    val isTv = detailedMedia.mediaType == "tv"
    val canRequest = !detailedMedia.requested && !detailedMedia.isRequesting && (!isTv || detailedMedia.seasons.isEmpty() || selectedSeasons.isNotEmpty())
    ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
          Box(Modifier.width(112.dp).height(168.dp).clip(RoundedCornerShape(10.dp))) {
            detailedMedia.posterPath?.let { RemoteImage("https://image.tmdb.org/t/p/w342$it", detailedMedia.title, Modifier.fillMaxSize(), ContentScale.Crop) }
              ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.RoundedFilled.Movie, null) }
            Column(Modifier.align(Alignment.TopCenter).padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
              when {
                detailedMedia.availableInJellyfin -> Surface(shape = RoundedCornerShape(5.dp), color = Color(0xFF2E7D32)) { Text("Available", Modifier.padding(horizontal = 5.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                detailedMedia.partiallyAvailable -> Surface(shape = RoundedCornerShape(5.dp), color = Color(0xFFE65100)) { Text("Partially available", Modifier.padding(horizontal = 5.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
              }
              when {
                detailedMedia.isRequesting -> Surface(shape = RoundedCornerShape(5.dp), color = Color(0xFFE65100)) { Text("Processing…", Modifier.padding(horizontal = 5.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                detailedMedia.requested -> Surface(shape = RoundedCornerShape(5.dp), color = Color(0xFF1B5E20)) { Text(if (detailedMedia.requested4k) "4K requested" else "Requested", Modifier.padding(horizontal = 5.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
              }
            }
            if (detailedMedia.availableInJellyfin) Surface(Modifier.align(Alignment.Center), CircleShape, Color.Black.copy(alpha = .72f)) { Icon(Icons.RoundedFilled.PlayArrow, "Play in “Mpv∞”", Modifier.padding(9.dp).size(25.dp), tint = Color.White) }
          }
          Column(Modifier.weight(1f)) {
            Text(detailedMedia.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            detailedMedia.releaseDate?.take(4)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            detailedMedia.voteAverage?.takeIf { it > 0 }?.let { Text("★ ${String.format(java.util.Locale.US, "%.1f", it)} / 10", color = Color(0xFFFFC107), fontWeight = FontWeight.Bold) }
            when {
              detailedMedia.availableInJellyfin -> Text("Available in Jellyfin", color = Color(0xFF43A047), fontWeight = FontWeight.Bold)
              detailedMedia.partiallyAvailable -> Text("Partially available", color = Color(0xFFFF9800), fontWeight = FontWeight.Bold)
            }
            if (detailedMedia.genres.isNotEmpty()) Text(detailedMedia.genres.joinToString("  •  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 2)
          }
        }
        Text("Request ${if (isTv) "TV Show" else "Movie"}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (isTv && detailedMedia.seasons.isNotEmpty()) {
          Text("Choose seasons", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            items(detailedMedia.seasons, key = { "season-${detailedMedia.id}-${it.seasonNumber}" }) { season ->
              Card(colors = CardDefaults.cardColors(containerColor = if (season.available) Color(0xFF1B5E20).copy(alpha = .18f) else MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), modifier = Modifier.width(126.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                  Text(season.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                  Text(when { season.available -> "Available"; season.requested -> "Requested"; else -> "Not available" }, style = MaterialTheme.typography.labelSmall, color = if (season.available) Color(0xFF43A047) else MaterialTheme.colorScheme.onSurfaceVariant)
                  OutlinedButton(onClick = { if (!season.available && !season.requested) selectedSeasons = if (season.seasonNumber in selectedSeasons) selectedSeasons - season.seasonNumber else selectedSeasons + season.seasonNumber }, enabled = !season.available && !season.requested, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.fillMaxWidth()) { Text(if (season.seasonNumber in selectedSeasons) "Selected" else if (season.available) "Available" else "Request") }
                }
              }
            }
          }
        }
        if (isTv && detailedMedia.genres.any { it.contains("anime", true) } || detailedMedia.title.contains("anime", true)) {
          Text("Anime audio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeerrAudioPreference.values().forEach { option ->
              OutlinedButton(onClick = { audioPreference = option }) { Text(if (audioPreference == option) "✓ ${option.label}" else option.label) }
            }
          }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
          Column(Modifier.weight(1f)) { Text("Request in 4K", style = MaterialTheme.typography.titleMedium); Text(if (is4k) "4K version selected · requires Seerr 4K permission" else "Standard version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
          Switch(checked = is4k, onCheckedChange = { is4k = it })
        }
        Button(onClick = { onRequest(detailedMedia, is4k, selectedSeasons.takeIf { isTv }?.toList(), audioPreference) }, enabled = canRequest, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text(if (detailedMedia.isRequesting) "Requesting…" else if (detailedMedia.requested) "Requested" else "Request ${if (isTv) "Selected Seasons" else "Movie"}") }
        if (detailedMedia.isRequesting) Text("Submitting request…", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        if (detailedMedia.requested) Text(if (detailedMedia.requested4k) "Request submitted for 4K" else "Request submitted", color = Color(0xFF43A047), fontWeight = FontWeight.SemiBold)
        detailedMedia.requestError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (detailedMedia.availableInJellyfin && detailedMedia.jellyfinMediaId != null) OutlinedButton(onClick = { onPlay(detailedMedia); selected = null }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Icon(Icons.RoundedFilled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Play in “Mpv∞”") }
        Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(detailedMedia.overview.ifBlank { "No overview available." }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
        if (detailedMedia.cast.isNotEmpty()) {
          Text("Cast", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(detailedMedia.cast, key = { "cast-${it.name}" }) { person ->
              Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                person.profilePath?.let { RemoteImage("https://image.tmdb.org/t/p/w185$it", person.name, Modifier.size(58.dp).clip(CircleShape), ContentScale.Crop) } ?: Surface(Modifier.size(58.dp), CircleShape, MaterialTheme.colorScheme.surfaceVariant) { Box(contentAlignment = Alignment.Center) { Icon(Icons.RoundedFilled.Person, null) } }
                Text(person.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                person.character?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
              }
            }
          }
        }
        Spacer(Modifier.height(12.dp))
      }
    }
  }
}


@Composable
private fun SeerrNativeSection(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun SeerrNativeRail(items: List<SeerrMediaItem>, onOpen: (SeerrMediaItem) -> Unit) { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(items, key = { "native-seerr-${it.mediaType}-${it.id}" }) { SeerrNativeCard(it, onOpen) } } }

@Composable
private fun SeerrNativeCard(item: SeerrMediaItem, onOpen: (SeerrMediaItem) -> Unit) {
  Column(Modifier.width(138.dp)) {
    Box(Modifier.fillMaxWidth().height(204.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable { onOpen(item) }) {
      item.posterPath?.let { RemoteImage("https://image.tmdb.org/t/p/w500$it", item.title, Modifier.fillMaxSize(), ContentScale.Crop) } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(if (item.mediaType == "tv") Icons.RoundedFilled.SmartDisplay else Icons.RoundedFilled.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
      item.voteAverage?.takeIf { it > 0 }?.let { Surface(Modifier.align(Alignment.TopStart).padding(6.dp), RoundedCornerShape(6.dp), Color.Black.copy(alpha = .75f)) { Text("★ ${String.format(java.util.Locale.US, "%.1f", it)}", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = Color(0xFFFFC107), style = MaterialTheme.typography.labelSmall) } }
      Column(Modifier.align(Alignment.TopCenter).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
          item.availableInJellyfin -> Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF2E7D32)) { Text("Available", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
          item.partiallyAvailable -> Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFE65100)) { Text("Partially available", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
        }
        when {
          item.isRequesting -> Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFE65100)) { Text("Processing…", Modifier.padding(horizontal = 5.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
          item.requestError != null -> Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFB71C1C)) { Text("Failed", Modifier.padding(horizontal = 5.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
          item.requested -> Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFF1B5E20)) { Text(if (item.requested4k) "4K requested" else "Requested", Modifier.padding(horizontal = 5.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall) }
        }
      }
      if (item.availableInJellyfin) Surface(Modifier.align(Alignment.Center), CircleShape, Color.Black.copy(alpha = .72f)) { Icon(Icons.RoundedFilled.PlayArrow, "Play in “Mpv∞”", Modifier.padding(12.dp).size(28.dp), tint = Color.White) }
    }
    Text(item.title, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
    Row(verticalAlignment = Alignment.CenterVertically) { Text(item.releaseDate?.take(4) ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)); Text(if (item.mediaType == "tv") "TV" else "Movie", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    OutlinedButton(onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("Details", maxLines = 1) }
  }
}
