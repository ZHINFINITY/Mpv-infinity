package app.infinity.mpvz.ui.downloads

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.domain.download.LinkDownloadCoordinator
import app.infinity.mpvz.preferences.YtdlPreferences
import app.infinity.mpvz.ui.theme.MpvInfinityTheme
import app.infinity.mpvz.utils.media.SharedUrlExtractor
import org.koin.android.ext.android.inject

class QuickDownloadActivity : ComponentActivity() {
  private val coordinator by inject<LinkDownloadCoordinator>()
  private val ytdlPreferences by inject<YtdlPreferences>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
    val sharedUri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.toString().orEmpty()
    val sharedUrl = SharedUrlExtractor.normalizeInput(sharedText.ifBlank { sharedUri })
    val title = sharedUrl.substringAfterLast('/').substringBefore('?').ifBlank { "Shared video" }
    val qualityEnabled = ytdlPreferences.showDownloadQualityChooser.get()

    setContent {
      MpvInfinityTheme {
        QuickDownloadPopup(
          url = sharedUrl,
          title = title,
          qualityEnabled = qualityEnabled,
          onCancel = { finish() },
          onDownload = { quality ->
            coordinator.enqueue(sharedUrl, title, quality)
            finish()
          },
        )
      }
    }
  }
}

@Composable
private fun QuickDownloadPopup(
  url: String,
  title: String,
  qualityEnabled: Boolean,
  onCancel: () -> Unit,
  onDownload: (Int) -> Unit,
) {
  var selectedQuality by remember { mutableIntStateOf(-1) }
  val qualityOptions = listOf(-1, 2160, 1440, 1080, 720, 480, 360)
  Card(
    modifier = Modifier.fillMaxWidth().padding(20.dp),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Quick Download", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
      Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
      if (qualityEnabled) {
        Text("Download quality", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        qualityOptions.forEach { quality ->
          Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected = selectedQuality == quality, onClick = { selectedQuality = quality })
            Text(if (quality < 0) "Best available" else "Up to ${quality}p")
          }
        }
      }
      Spacer(modifier = Modifier.height(4.dp))
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = { onDownload(if (qualityEnabled) selectedQuality else -1) }) { Text("Quick Download") }
      }
    }
  }
}
