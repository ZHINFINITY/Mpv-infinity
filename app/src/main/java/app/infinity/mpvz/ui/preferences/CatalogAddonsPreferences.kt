package app.infinity.mpvz.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.catalog.CatalogSource
import app.infinity.mpvz.catalog.CatalogViewModel
import kotlinx.coroutines.launch

@Composable
fun CatalogAddonsPreferenceCard(
  viewModel: CatalogViewModel,
  sources: List<CatalogSource>,
) {
  var showAddDialog by remember { mutableStateOf(false) }
  var isAdding by remember { mutableStateOf(false) }
  var newUrl by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()

  PreferenceCard {
    Column(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Text("Catalog add-ons", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
      Text(
        "These Nuvio-compatible catalog sources power Stream home rails, search and metadata. JavaScript video providers are managed separately below.",
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    sources.forEachIndexed { index, source ->
      if (index > 0) PreferenceDivider()
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(source.name, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(source.manifestUrl, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
          TextButton(onClick = { viewModel.removeCatalogSource(source.id) }) {
            Text("Remove", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
          }
        }
        Switch(checked = source.isEnabled, onCheckedChange = { viewModel.setCatalogSourceEnabled(source.id, it) })
      }
    }
    if (sources.isNotEmpty()) PreferenceDivider()
    androidx.compose.material3.TextButton(
      onClick = { error = null; newUrl = ""; showAddDialog = true },
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) { Text("Add catalog add-on") }
  }

  if (showAddDialog) {
    AlertDialog(
      onDismissRequest = { if (!isAdding) showAddDialog = false },
      title = { Text("Add catalog add-on") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Paste a Stremio-compatible catalog add-on or manifest URL. Its catalogs are used for Stream discovery and search.")
          OutlinedTextField(
            value = newUrl,
            onValueChange = { newUrl = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Add-on or manifest URL") },
            placeholder = { Text("https://example.com/manifest.json") },
          )
          error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        }
      },
      confirmButton = {
        TextButton(
          enabled = newUrl.isNotBlank() && !isAdding,
          onClick = {
            isAdding = true
            scope.launch {
              viewModel.addCatalogSource(newUrl)
                .onSuccess { showAddDialog = false }
                .onFailure { error = it.message ?: "Could not install this catalog add-on." }
              isAdding = false
            }
          },
        ) {
          if (isAdding) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
          Text(if (isAdding) "Installing" else "Install")
        }
      },
      dismissButton = { TextButton(enabled = !isAdding, onClick = { showAddDialog = false }) { Text("Cancel") } },
    )
  }
}
