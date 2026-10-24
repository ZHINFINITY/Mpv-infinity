package app.infinity.mpvz.ui.theme

import android.os.Looper

/**
 * One compiled glass program per source, shared by every pane.
 *
 * Compiling a program parses SkSL on the calling thread. Building one per pane made
 * each player sheet compile a shader for every glass row/chip at first composition.
 * Sharing is safe because each pane sets all of its uniforms immediately before
 * recording. Compose records on the main thread only, so access is confined to it.
 *
 * Values are untyped so this class never mentions RuntimeShader and can load on
 * Android 10, where that class does not exist.
 */
object GlassShaderCache {
  private val compiled = HashMap<String, Any?>()

  fun <T> shared(source: String, create: () -> T): T? {
    if (Looper.myLooper() != Looper.getMainLooper()) return runCatching(create).getOrNull()
    @Suppress("UNCHECKED_CAST")
    return compiled.getOrPut(source) { runCatching(create).getOrNull() } as T?
  }
}
