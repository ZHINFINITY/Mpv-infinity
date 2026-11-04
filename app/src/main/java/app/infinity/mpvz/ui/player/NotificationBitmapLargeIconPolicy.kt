package app.infinity.mpvz.ui.player

/**
 * Avoids publishing Bitmap-backed artwork to Android system media surfaces on Android 16 target
 * devices where Binder/ashmem bitmap transfer has caused process aborts in fdsan.
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
    val isPoco =
      normalizedManufacturer.equals("Xiaomi", ignoreCase = true) &&
        normalizedModel.equals("25053PC47I", ignoreCase = true)
    val isI2405 =
      (normalizedManufacturer.equals("vivo", ignoreCase = true) ||
        normalizedManufacturer.equals("iQOO", ignoreCase = true)) &&
        normalizedModel.equals("I2405", ignoreCase = true)
    return isPoco || isI2405
  }
}
