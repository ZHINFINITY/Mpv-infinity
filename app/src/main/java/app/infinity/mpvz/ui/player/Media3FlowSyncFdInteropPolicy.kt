package app.infinity.mpvz.ui.player

/** Avoids native sync-FD handoff on the affected Android 16 vivo I2405; other devices retain the existing path. */
internal object Media3FlowSyncFdInteropPolicy {
  fun allowNativeSyncFdInterop(apiLevel: Int, manufacturer: String, model: String): Boolean {
    val affectedAndroid16Device = apiLevel >= 36 &&
      manufacturer.trim().equals("vivo", ignoreCase = true) &&
      model.trim().equals("I2405", ignoreCase = true)
    return !affectedAndroid16Device
  }
}
