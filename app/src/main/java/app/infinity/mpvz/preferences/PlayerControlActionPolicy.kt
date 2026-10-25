/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.preferences

/** Shared name-based persistence; new actions never rewrite a user's existing control layout. */
internal object PlayerControlActionPolicy {
  fun <T> parse(csv: String, used: MutableSet<T>, placeholder: T, resolve: (String) -> T?): List<T> =
    csv.splitToSequence(',')
      .map { it.trim().uppercase() }
      .mapNotNull(resolve)
      .filter { it != placeholder }
      .filter { used.add(it) }
      .toList()

  /** Mirrors Decoder settings: an enabled feature can always be switched off. */
  fun interpolationAvailable(
    currentlyEnabled: Boolean,
    vulkanAvailable: Boolean,
    nativeEngine: Boolean,
    mpvRuntimeAvailable: Boolean,
    configOwned: Boolean,
    anime4kActive: Boolean,
  ): Boolean = currentlyEnabled ||
    (vulkanAvailable && (nativeEngine || (mpvRuntimeAvailable && !configOwned && !anime4kActive)))
}
