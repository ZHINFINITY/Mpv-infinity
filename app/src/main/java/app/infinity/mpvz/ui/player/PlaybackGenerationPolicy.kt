/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

/** Includes core retirement; restarting libmpv must never reuse an old load token. */
internal object PlaybackGenerationPolicy {
  fun next(current: Long): Long = Math.addExact(current, 1L)
}
