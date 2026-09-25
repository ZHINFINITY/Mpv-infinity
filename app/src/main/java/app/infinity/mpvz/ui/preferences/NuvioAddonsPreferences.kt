package app.infinity.mpvz.ui.preferences

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.catalog.CatalogSource
import app.infinity.mpvz.catalog.isHttpAddonEndpoint
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.PreferenceCard
import me.zhanghai.compose.preference.PreferenceDivider

@Composable
fun NuvioAddonsPreferenceCard(
  sources: List<CatalogSource>,
  onSourcesChanged: (List<CatalogSource>) -> Unit,
) {
  var isAddDialogOpen by remember { mutableStateOf(false) }

  PreferenceCard {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        "Install Stremio-compatible add-ons to provide Stream home catalogs, metadata, episode lists and direct HTTPS streams.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (sources.isNotEmpty()) PreferenceDivider()
    sources.forEachIndexed { index, source ->
      if (index > 0) PreferenceDivider()
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Switch(
          checked = source.isEnabled,
          onCheckedChange = { enabled ->
            onSourcesChanged(sources.toMutableList().also { it[index] = source.copy(isEnabled = enabled) })
          },
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(source.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(source.manifestUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = { onSourcesChanged(sources.filterIndexed { sourceIndex, _ -> sourceIndex != index }) }) {
          Text("Remove", color = MaterialTheme.colorScheme.error)
        }
      }
    }
    if (sources.isNotEmpty()) PreferenceDivider()
    Preference(
      title = { Text("Add add-on") },
      summary = { Text("Add a manifest URL; catalogs and metadata will be loaded automatically.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
      icon = {
        androidx.compose.material3.Icon(
          imageVector = app.infinity.mpvz.ui.icons.Icons.RoundedFilled.Add,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
      },
      onClick = { isAddDialogOpen = true },
    )
  }

  if (isAddDialogOpen) {
    AddNuvioAddonDialog(
      onDismiss = { isAddDialogOpen = false },
      onAdd = { rawUrl ->
        val manifestUrl = normalizeNuvioManifestUrl(rawUrl)
        if (manifestUrl != null && sources.none { it.manifestUrl.equals(manifestUrl, ignoreCase = true) }) {
          val host = manifestUrl.substringAfter("://").substringBefore('/').substringBefore('?')
          val pathLabel = manifestUrl.substringAfter("://").substringAfter('/', "").substringBefore("/manifest.json").substringBefore('?').trim('/')
          val label = listOf(host, pathLabel.takeIf { it.isNotBlank() }).filterNotNull().joinToString("/").ifBlank { "Nuvio add-on" }
          val id = "addon-${manifestUrl.hashCode()}"
          onSourcesChanged(sources + CatalogSource(id, label, manifestUrl))
        }
        isAddDialogOpen = false
      },
    )
  }
}

@Composable
private fun AddNuvioAddonDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
  var url by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add Nuvio add-on") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Paste the add-on URL or manifest URL. Stremio add-on catalogs, metadata and stream resources are supported.")
        OutlinedTextField(
          value = url,
          onValueChange = { url = it; error = null },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          label = { Text("Add-on URL") },
          placeholder = { Text("https://example.com/manifest.json") },
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
    },
    confirmButton = {
      Button(onClick = {
        val normalized = normalizeNuvioManifestUrl(url)
        if (normalized == null || !isHttpAddonEndpoint(normalized)) error = "Enter a valid HTTP(S) add-on URL."
        else onAdd(url)
      }) { Text("Add") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

private fun normalizeNuvioManifestUrl(rawUrl: String): String? {
  val trimmed = rawUrl.trim()
  if (trimmed.isBlank()) return null
  val noFragment = trimmed.substringBefore('#')
  val withScheme = when {
    noFragment.startsWith("http://", ignoreCase = true) || noFragment.startsWith("https://", ignoreCase = true) -> noFragment
    noFragment.startsWith("stremio://", ignoreCase = true) -> "https://${noFragment.substringAfter("://")}" 
    else -> "https://$noFragment"
  }
  val query = withScheme.substringAfter('?', "")
  val path = withScheme.substringBefore('?').trimEnd('/')
  val manifestPath = if (path.endsWith("/manifest.json", ignoreCase = true)) path else "$path/manifest.json"
  val result = if (query.isBlank()) manifestPath else "$manifestPath?$query"
  return result.takeIf { isHttpAddonEndpoint(it) && !Uri.parse(it).host.isNullOrBlank() }
}
