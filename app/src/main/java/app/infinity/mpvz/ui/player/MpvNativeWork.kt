/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Only the authorized MPV restart's native work. Never put View/SurfaceHolder work here. */
internal class MpvNativeWork(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
  private val serial = Mutex()

  suspend fun <T> execute(block: suspend () -> T): T = withContext(dispatcher) {
    serial.withLock { block() }
  }
}
