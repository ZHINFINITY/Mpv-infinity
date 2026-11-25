package app.infinity.mpvz.utils.device

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceFormFactor {
  @Volatile private var cachedTelevisionFeature: Boolean? = null

  fun isTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

    cachedTelevisionFeature?.let { return it }
    return synchronized(this) {
      cachedTelevisionFeature ?: context.packageManager.let { packageManager ->
        (
          packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
        ).also { cachedTelevisionFeature = it }
      }
    }
  }
}
