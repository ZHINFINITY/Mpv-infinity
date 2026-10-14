/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.cast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.infinity.mpvz.ui.theme.MpvInfinityTheme

class CastRemoteControllerActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val controller = CastPlaybackController.instance
    if (controller == null) {
      finish()
      return
    }

    setContent {
      MpvInfinityTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          val castState by controller.castState.collectAsState()

          CastRemoteControllerScreen(
            castState = castState,
            controller = controller,
            onBackClick = { finish() },
            onStopCasting = {
              controller.disconnect()
              finish()
            },
          )
        }
      }
    }
  }
}
