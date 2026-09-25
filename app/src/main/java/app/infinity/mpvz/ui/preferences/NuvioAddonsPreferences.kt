package app.infinity.mpvz.ui.preferences

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.catalog.nuvio.PluginRepository
import app.infinity.mpvz.catalog.nuvio.PluginSettingField
import app.infinity.mpvz.catalog.nuvio.PluginScraper
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.Preference
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons

@Composable
fun NuvioAddonsPreferenceCard() {
  val context = LocalContext.current
  val repository = remember(context) { PluginRepository(context.applicationContext) }
  val state by repository.uiState.collectAsState()
  val scope = rememberCoroutineScope()
  var showAddDialog by remember { mutableStateOf(false) }
  var isRefreshing by remember { mutableStateOf(false) }
  var tmdbApiKey by remember(repository) { mutableStateOf(repository.tmdbApiKey()) }
  var isSavingTmdbKey by remember { mutableStateOf(false) }
  var tmdbSaveMessage by remember { mutableStateOf<String?>(null) }
  var tmdbSaveFailed by remember { mutableStateOf(false) }
  var configuringProvider by remember { mutableStateOf<PluginScraper?>(null) }
  var providerSettingsLayout by remember { mutableStateOf<List<PluginSettingField>>(emptyList()) }
  var providerSettingsError by remember { mutableStateOf<String?>(null) }
  val providerSettingsValues = remember(configuringProvider?.id) { mutableStateMapOf<String, String>().apply { configuringProvider?.id?.let { putAll(repository.scraperSettings(it)) } } }

  PreferenceCard {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
      Text("Nuvio scraper repositories", style = MaterialTheme.typography.titleMedium)
      Text("Repositories are downloaded and split into their individual JavaScript providers. Providers return direct video links; Stremio catalog add-ons are managed separately above.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("TMDB API key", style = MaterialTheme.typography.titleSmall)
      Text("Some Nuvio providers call TMDB directly for title details. Add your TMDB API key if those providers return no sources; it is stored encrypted on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedTextField(value = tmdbApiKey, onValueChange = { tmdbApiKey = it; tmdbSaveMessage = null }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation())
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(enabled = !isSavingTmdbKey, onClick = {
          isSavingTmdbKey = true
          tmdbSaveMessage = null
          scope.launch {
            val saved = runCatching { repository.setTmdbApiKey(tmdbApiKey) }.getOrDefault(false)
            isSavingTmdbKey = false
            tmdbSaveFailed = !saved
            tmdbSaveMessage = if (saved) "Saved securely on this device." else "Could not save the key. Try again."
          }
        }) {
          if (isSavingTmdbKey) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
          Text(if (isSavingTmdbKey) "Saving…" else "Save API key")
        }
        tmdbSaveMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (tmdbSaveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
      }
    }
    PreferenceDivider()
    state.repositories.forEachIndexed { index, repo ->
      PreferenceDivider()
      var expanded by rememberSaveable(repo.manifestUrl) { mutableStateOf(false) }
      Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(repo.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${repo.scraperCount} providers${repo.version?.let { " · v$it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val repoHost = runCatching { java.net.URI(repo.manifestUrl).host }.getOrNull()
            if (!repoHost.isNullOrBlank()) Text(repoHost, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          if (repo.isRefreshing || isRefreshing) CircularProgressIndicator(Modifier.padding(6.dp), strokeWidth = 2.dp)
          Icon(if (expanded) Icons.RoundedFilled.ExpandLess else Icons.RoundedFilled.ExpandMore, contentDescription = if (expanded) "Collapse providers" else "Expand providers")
        }
        AnimatedVisibility(visible = expanded) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repo.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val providers = state.scrapers.filter { it.repositoryUrl == repo.manifestUrl }
            if (providers.isEmpty() && repo.scraperCount > 0) {
              Text("Provider scripts have not loaded yet. Refresh this repository to retry.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            providers.forEach { provider ->
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(checked = provider.enabled, enabled = provider.manifestEnabled, onCheckedChange = { repository.toggleScraper(provider.id, it) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(provider.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(provider.description.ifBlank { "Supports ${provider.supportedTypes.joinToString()}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
              Text("${provider.supportedTypes.joinToString(" · ")}  |  v${provider.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (provider.hasSettings) TextButton(onClick = {
              providerSettingsError = null
              scope.launch {
                runCatching { repository.settingsLayout(provider.id) }
                  .onSuccess { fields ->
                    configuringProvider = provider
                    providerSettingsLayout = fields
                    fields.forEach { field ->
                      val key = field.key ?: return@forEach
                      if (key !in providerSettingsValues && field.defaultValue != null) providerSettingsValues[key] = field.defaultValue
                    }
                  }
                  .onFailure { providerSettingsError = it.message ?: "Could not load provider settings." }
              }
            }) { Text("Settings") }
          }
        }
            repo.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              TextButton(enabled = !repo.isRefreshing, onClick = { scope.launch { repository.refreshRepository(repo.manifestUrl) } }) { Text("Refresh providers") }
              TextButton(onClick = { repository.removeRepository(repo.manifestUrl) }) { Text("Remove repository", color = MaterialTheme.colorScheme.error) }
            }
          }
        }
      }
    }
    PreferenceDivider()
    Preference(
      title = { Text("Add Nuvio provider repository") },
      summary = { Text("Paste a repository URL or manifest URL. Individual providers will appear here after installation.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
      icon = { Icon(Icons.RoundedFilled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
      onClick = { showAddDialog = true },
    )
    if (state.repositories.isNotEmpty()) {
      PreferenceDivider()
      Preference(
        title = { Text("Refresh all provider repositories") },
        summary = { Text("Re-download manifests and provider code.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        icon = { Icon(Icons.RoundedFilled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        onClick = {
          isRefreshing = true
          scope.launch {
            try { repository.refreshAll() } finally { isRefreshing = false }
          }
        },
      )
    }
  }

  if (showAddDialog) {
    AddNuvioProviderRepositoryDialog(
      onDismiss = { showAddDialog = false },
      onAdd = { url, done ->
        scope.launch {
          val result = repository.addRepository(url)
          done(result.exceptionOrNull()?.message)
          if (result.isSuccess) showAddDialog = false
        }
      },
    )
  }
  configuringProvider?.let { provider ->
    PluginProviderSettingsDialog(
      provider = provider,
      fields = providerSettingsLayout,
      values = providerSettingsValues,
      error = providerSettingsError,
      onDismiss = { configuringProvider = null },
      onSave = {
        val saved = providerSettingsLayout.mapNotNull { field -> field.key?.let { key -> key to (providerSettingsValues[key] ?: field.defaultValue.orEmpty()) } }.toMap()
        repository.saveScraperSettings(provider.id, saved)
        configuringProvider = null
      },
    )
  }
}

@Composable
private fun PluginProviderSettingsDialog(
  provider: PluginScraper,
  fields: List<PluginSettingField>,
  values: MutableMap<String, String>,
  error: String?,
  onDismiss: () -> Unit,
  onSave: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("${provider.name} settings") },
    text = {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          fields.forEachIndexed { index, field ->
            val key = field.key
            val value = key?.let { values[it] ?: field.defaultValue.orEmpty() }.orEmpty()
            when (field.type.lowercase()) {
              "header" -> Text(field.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
              "description", "info" -> Text(field.description ?: field.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              "select", "dropdown" -> {
                var expanded by remember(provider.id, index) { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                  Text(field.label, style = MaterialTheme.typography.labelLarge)
                  Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = key != null) { Text(field.options.firstOrNull { it.value == value }?.label ?: value.ifBlank { field.placeholder ?: "Select" }, Modifier.fillMaxWidth()) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                      field.options.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { if (key != null) values[key] = option.value; expanded = false }) }
                    }
                  }
                  field.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
              }
              "toggle", "switch", "checkbox", "boolean" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { Text(field.label, style = MaterialTheme.typography.titleSmall); field.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                Switch(checked = value.equals("true", true), onCheckedChange = { if (key != null) values[key] = it.toString() }, enabled = key != null)
              }
              else -> OutlinedTextField(value = value, onValueChange = { if (key != null) values[key] = it }, modifier = Modifier.fillMaxWidth(), label = { Text(field.label.ifBlank { key ?: "Setting" }) }, placeholder = field.placeholder?.let { { Text(it) } }, supportingText = field.description?.let { description -> { Text(description) } }, singleLine = true, enabled = key != null)
            }
          }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
    },
    confirmButton = { Button(onClick = onSave) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun AddNuvioProviderRepositoryDialog(onDismiss: () -> Unit, onAdd: (String, (String?) -> Unit) -> Unit) {
  var url by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text("Add Nuvio provider repository") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Paste a Nuvio JavaScript scraper repository URL. This is not a Stremio manifest URL.")
        OutlinedTextField(value = url, onValueChange = { url = it; error = null }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Repository URL") }, placeholder = { Text("https://raw.githubusercontent.com/owner/repo/refs/heads/main") }, enabled = !busy)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
    },
    confirmButton = {
      Button(enabled = !busy && url.isNotBlank(), onClick = {
        busy = true
        onAdd(url) { message -> busy = false; error = message }
      }) { if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp) else Unit; Text(if (busy) "Installing…" else "Install providers") }
    },
    dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
  )
}
