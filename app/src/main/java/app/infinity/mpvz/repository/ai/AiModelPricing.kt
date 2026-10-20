/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.repository.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object AiModelPricing {
  fun isZeroCost(pricing: JsonObject?): Boolean {
    if (pricing == null || pricing.isEmpty()) return false

    var sawNumericPrice = false
    for ((_, value) in pricing) {
      val primitive = value as? JsonPrimitive ?: continue
      val number = primitive.contentOrNull?.toDoubleOrNull() ?: continue
      sawNumericPrice = true
      if (number != 0.0) return false
    }

    return sawNumericPrice
  }
}
