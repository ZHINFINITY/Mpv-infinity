package app.infinity.mpvz.ui.browser.jellyfin

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeerrNativeDashboard(
  state: SeerrDiscoverState,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onRequest: (SeerrMediaItem) -> Unit,
  onDisconnect: () -> Unit,
) {
  var selected by remember { mutableStateOf<SeerrMediaItem?>(null) }
  var query by remember { mutableStateOf("") }
  val filtered = { source: List<SeerrMediaItem> ->
    if (query.isBlank()) source else source.filter { it.title.contains(query, ignoreCase = true) }
  }
  BackHandler(onBack = onBack)

  Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    TopAppBar(
      title = { Text("Discover", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) },
      navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.RoundedFilled.ArrowBack, "Back") } },
      actions = {
        IconButton(onClick = onRefresh) { Icon(Icons.RoundedFilled.Refresh, "Refresh") }
        IconButton(onClick = onDisconnect) { Icon(Icons.RoundedFilled.LinkOff, "Disconnect Seerr") }
      },
      colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      singleLine = true,
      shape = RoundedCornerShape(24.dp),
      placeholder = { Text("Search movies and TV shows in Seerr") },
      leadingIcon = { Icon(Icons.RoundedFilled.Search, null) },
    )
    if (state.isLoading && state.movies.isEmpty() && state.shows.isEmpty()) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (state.error != null && state.movies.isEmpty() && state.shows.isEmpty()) {
      Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(state.error, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRefresh) { Text("Retry") }
      }
    } else {
      LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { SeerrAccountBanner(state = state, onDisconnect = onDisconnect) }
        if (filtered(state.trending).isNotEmpty()) {
          item { SeerrNativeSection("Trending", "Popular on Seerr") }
          item { SeerrNativeRail(filtered(state.trending), onRequest) { selected = it } }
        }
        if (filtered(state.movies).isNotEmpty()) {
          item { SeerrNativeSection("Movies", "Discover movies") }
          item { SeerrNativeRail(filtered(state.movies), onRequest) { selected = it } }
        }
        if (filtered(state.shows).isNotEmpty()) {
          item { SeerrNativeSection("TV Shows", "Discover series") }
          item { SeerrNativeRail(filtered(state.shows), onRequest) { selected = it } }
        }
      }
    }
  }

  selected?.let { media ->
    ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
      Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(media.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        media.releaseDate?.take(4)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        media.voteAverage?.takeIf { it > 0 }?.let { Text("★ ${String.format(java.util.Locale.US, "%.1f", it)} / 10", color = Color(0xFFFFC107), fontWeight = FontWeight.Bold) }
        if (media.overview.isNotBlank()) Text(media.overview, maxLines = 6, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { onRequest(media); selected = null }, enabled = !media.requested, modifier = Modifier.fillMaxWidth()) {
          Text(if (media.requested) "Requested" else "Request ${if (media.mediaType == "tv") "TV Show" else "Movie"}")
        }
        Spacer(Modifier.height(12.dp))
      }
    }
  }
}

@Composable
private fun SeerrAccountBanner(state: SeerrDiscoverState, onDisconnect: () -> Unit) {
  Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Surface(Modifier.size(44.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.RoundedFilled.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
      }
      Column(Modifier.weight(1f).padding(start = 12.dp)) {
        Text(state.userName ?: "Seerr", fontWeight = FontWeight.Bold)
        Text("Connected to Seerr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      IconButton(onClick = onDisconnect) { Icon(Icons.RoundedFilled.LinkOff, "Disconnect") }
    }
  }
}

@Composable
private fun SeerrNativeSection(title: String, subtitle: String) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun SeerrNativeRail(items: List<SeerrMediaItem>, onRequest: (SeerrMediaItem) -> Unit, onOpen: (SeerrMediaItem) -> Unit) {
  LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    items(items, key = { "native-seerr-${it.mediaType}-${it.id}" }) { item ->
      SeerrNativeCard(item, onRequest, onOpen)
    }
  }
}

@Composable
private fun SeerrNativeCard(item: SeerrMediaItem, onRequest: (SeerrMediaItem) -> Unit, onOpen: (SeerrMediaItem) -> Unit) {
  Column(Modifier.width(138.dp)) {
    Box(Modifier.fillMaxWidth().height(204.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable { onOpen(item) }) {
      item.posterPath?.let { path ->
        RemoteImage("https://image.tmdb.org/t/p/w500$path", item.title, Modifier.fillMaxSize(), ContentScale.Crop)
      } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(if (item.mediaType == "tv") Icons.RoundedFilled.Tv else Icons.RoundedFilled.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
      item.voteAverage?.takeIf { it > 0 }?.let { rating ->
        Surface(Modifier.align(Alignment.TopStart).padding(6.dp), shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = .75f)) { Text("★ ${String.format(java.util.Locale.US, "%.1f", rating)}", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = Color(0xFFFFC107), style = MaterialTheme.typography.labelSmall) }
      }
      if (item.requested) Surface(Modifier.align(Alignment.TopEnd).padding(6.dp), shape = RoundedCornerShape(6.dp), color = Color(0xFF1B5E20)) { Text("Requested", Modifier.padding(horizontal = 5.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall) }
    }
    Text(item.title, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(item.releaseDate?.take(4) ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.width(4.dp))
      Text(if (item.mediaType == "tv") "TV" else "Movie", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    OutlinedButton(onClick = { onRequest(item) }, enabled = !item.requested, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text(if (item.requested) "Requested" else "Request", maxLines = 1) }
  }
}
