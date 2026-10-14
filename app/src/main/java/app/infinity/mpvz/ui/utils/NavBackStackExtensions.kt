/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.utils

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * Pops the current entry without ever leaving the NavDisplay with an empty stack.
 *
 * Returns true when an entry was removed, or false when the caller is already at the root.
 */
fun NavBackStack<*>.popSafely(): Boolean {
  if (size <= 1) return false

  removeLastOrNull()
  return true
}

/**
 * Replaces the current top entry with [screen] instead of pushing on top of it.
 *
 * Used when a screen is a transient gate/step (e.g. the Secure Folder PIN screen) that
 * shouldn't remain in the back stack once its job is done — otherwise pressing back from the
 * destination screen would land back on the gate instead of whatever was open before it.
 */
fun <T : NavKey> NavBackStack<T>.replaceTop(screen: T) {
  if (isEmpty()) {
    add(screen)
  } else {
    this[lastIndex] = screen
  }
}
