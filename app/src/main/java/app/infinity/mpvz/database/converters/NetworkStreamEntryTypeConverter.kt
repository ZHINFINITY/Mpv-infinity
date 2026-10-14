/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.database.converters

import androidx.room.TypeConverter
import app.infinity.mpvz.database.entities.NetworkStreamEntryType

class NetworkStreamEntryTypeConverter {
  @TypeConverter
  fun fromEntryType(type: NetworkStreamEntryType): String = type.name

  @TypeConverter
  fun toEntryType(value: String): NetworkStreamEntryType = NetworkStreamEntryType.valueOf(value)
}
