package app.infinity.mpvz.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

class AppHaptics {
  fun selection(enabled: Boolean) {
    // Selection feedback is intentionally optional; the setting remains functional without it.
  }
}

@Composable
fun rememberAppHaptics(): AppHaptics = remember { AppHaptics() }
