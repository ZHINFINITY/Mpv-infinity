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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
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
import app.infinity.mpvz.ui.player.PlayerActivity
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
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
    if (state.isLoading) Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    LazyVerticalGrid(
      columns = GridCells.Adaptive(130.dp),
      contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      items(state.items, key = { "${it.type}-${it.id}" }) { item ->
        CatalogCard(item, state.resolvingId == item.id) { viewModel.resolve(item) }
      }
    }
  }
  if (showSettings) CatalogSettingsDialog(viewModel) { showSettings = false }
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
private fun CatalogSettingsDialog(viewModel: CatalogViewModel, onDismiss: () -> Unit) {
  val initial = remember { viewModel.currentSettings() }
  var tmdbKey by remember { mutableStateOf(initial.first) }
  var resolverUrl by remember { mutableStateOf(initial.second) }
  var resolverToken by remember { mutableStateOf(initial.third) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Catalog and resolver") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Keys and tokens are stored with Android encrypted preferences.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(tmdbKey, { tmdbKey = it }, label = { Text("TMDB API key") }, singleLine = true)
        OutlinedTextField(resolverUrl, { resolverUrl = it }, label = { Text("Resolver HTTPS base URL") }, singleLine = true)
        OutlinedTextField(resolverToken, { resolverToken = it }, label = { Text("Resolver bearer token") }, singleLine = true)
      }
    },
    confirmButton = { Button(onClick = { viewModel.saveSettings(tmdbKey, resolverUrl, resolverToken); onDismiss() }) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
