/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.player

/** Identity captured before a native callback is queued onto the UI thread. */
internal data class PlayerObserverCallbackToken(val bindingEpoch: Long, val generation: Long)

internal object PlayerObserverCallbackPolicy {
  /** Generation zero is an idle generation, never a wildcard for a later media request. */
  fun isCurrent(
    token: PlayerObserverCallbackToken,
    currentBindingEpoch: Long,
    currentGeneration: Long,
  ): Boolean = token.bindingEpoch == currentBindingEpoch && token.generation == currentGeneration
}
