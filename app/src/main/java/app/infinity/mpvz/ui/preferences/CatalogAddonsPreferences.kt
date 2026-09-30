package app.infinity.mpvz.ui.preferences

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.catalog.isDefaultBuiltinCatalogSource
import app.infinity.mpvz.catalog.CatalogSource
import app.infinity.mpvz.catalog.CatalogViewModel
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogAddonsPreferenceCard(viewModel: CatalogViewModel, sources: List<CatalogSource>) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  var showAddSheet by rememberSaveable { mutableStateOf(false) }
  var isAdding by remember { mutableStateOf(false) }
  var newUrl by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()

  PreferenceCard {
    Column(Modifier.fillMaxWidth().animateContentSize()) {
      Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text("Stream catalogs", style = MaterialTheme.typography.titleMedium)
          Text("${sources.count { it.isEnabled }} enabled · ${sources.size} configured", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (expanded) Icons.RoundedFilled.ExpandLess else Icons.RoundedFilled.ExpandMore, contentDescription = if (expanded) "Collapse catalogs" else "Expand catalogs")
      }
      AnimatedVisibility(visible = expanded) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Cinemeta is included by default, and enabled user add-ons load independently alongside it. Streaming providers are managed separately.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          sources.forEachIndexed { index, source ->
            if (index > 0) PreferenceDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(source.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (isDefaultBuiltinCatalogSource(source)) "Built-in catalog" else source.manifestUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!isDefaultBuiltinCatalogSource(source)) {
                  TextButton(onClick = { viewModel.removeCatalogSource(source.id) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
              }
              Switch(checked = source.isEnabled, onCheckedChange = { viewModel.setCatalogSourceEnabled(source.id, it) })
            }
          }
          PreferenceDivider()
          TextButton(onClick = { error = null; newUrl = ""; showAddSheet = true }, modifier = Modifier.fillMaxWidth()) { Text("Add catalog add-on") }
        }
      }
    }
  }

  if (showAddSheet) {
    ModalBottomSheet(
      onDismissRequest = { if (!isAdding) showAddSheet = false },
      shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
      Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Add catalog add-on", style = MaterialTheme.typography.headlineSmall)
        Text("Paste a Stremio-compatible catalog or manifest URL. Its catalogs appear in Stream discovery and search.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = newUrl, onValueChange = { newUrl = it; error = null }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Add-on URL") }, placeholder = { Text("https://example.com/manifest.json") }, enabled = !isAdding)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
          TextButton(enabled = !isAdding, onClick = { showAddSheet = false }) { Text("Cancel") }
          Button(enabled = newUrl.isNotBlank() && !isAdding, onClick = {
            isAdding = true
            scope.launch {
              viewModel.addCatalogSource(newUrl)
                .onSuccess { showAddSheet = false }
                .onFailure { error = it.message ?: "Could not install this catalog add-on." }
              isAdding = false
            }
          }) {
            if (isAdding) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
            Text(if (isAdding) "Installing…" else "Install")
          }
        }
      }
    }
  }
}
