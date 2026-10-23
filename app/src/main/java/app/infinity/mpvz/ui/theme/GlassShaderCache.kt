package app.infinity.mpvz.ui.theme

import android.graphics.RuntimeShader
import android.os.Looper

/**
 * One compiled [RuntimeShader] per glass program, shared by every pane.
 *
 * Constructing a RuntimeShader parses and compiles SkSL on the calling thread. Building
 * one per pane made each player sheet compile a shader for every glass row/chip at first
 * composition, which delayed the open animation. Sharing is safe because each pane sets
 * all of its uniforms immediately before recording (Paint shader) or creating its
 * RenderEffect, and both snapshot the uniforms into a native object at that moment.
 * Compose records on the main thread only, so access is confined to it.
 */
object GlassShaderCache {
  private val compiled = HashMap<String, RuntimeShader?>()

  fun shared(source: String, create: () -> RuntimeShader): RuntimeShader? {
    if (Looper.myLooper() != Looper.getMainLooper()) return runCatching(create).getOrNull()
    return compiled.getOrPut(source) { runCatching(create).getOrNull() }
  }
}
