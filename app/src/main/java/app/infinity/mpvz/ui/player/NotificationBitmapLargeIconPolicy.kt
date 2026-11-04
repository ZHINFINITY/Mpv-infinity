package app.infinity.mpvz.ui.player

/**
 * Avoids parceling Bitmap-backed notification icons on the Android 16 Xiaomi POCO and vivo I2405
 * devices where NotificationManager bitmap serialization aborts in ashmem/fdsan.
 */
internal object NotificationBitmapLargeIconPolicy {
  fun shouldOmitBitmapLargeIcon(
    apiLevel: Int,
    manufacturer: String,
    model: String,
  ): Boolean {
    if (apiLevel < 36) return false

    val normalizedManufacturer = manufacturer.trim()
    val normalizedModel = model.trim()
    return (normalizedManufacturer.equals("Xiaomi", ignoreCase = true) &&
      normalizedModel.equals("25053PC47I", ignoreCase = true)) ||
      (normalizedManufacturer.equals("vivo", ignoreCase = true) &&
        normalizedModel.equals("I2405", ignoreCase = true))
  }
}
