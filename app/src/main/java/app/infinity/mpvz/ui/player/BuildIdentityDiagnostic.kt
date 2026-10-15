package app.infinity.mpvz.ui.player

internal object BuildIdentityDiagnostic {
  fun shaField(gitSha: String): String = "build_sha=$gitSha"

  fun startupLine(
    gitSha: String,
    versionName: String,
    versionCode: Int,
    deviceBrand: String,
    deviceModel: String,
    deviceProduct: String,
    deviceCodeName: String,
    androidRelease: String,
    androidSdk: Int,
    buildFingerprint: String,
  ): String =
    "build_identity ${shaField(gitSha)} version_name=$versionName version_code=$versionCode " +
      "device_brand=${fieldValue(deviceBrand)} device_model=${fieldValue(deviceModel)} " +
      "device_product=${fieldValue(deviceProduct)} device_codename=${fieldValue(deviceCodeName)} " +
      "android_release=${fieldValue(androidRelease)} android_sdk=$androidSdk " +
      "build_fingerprint=${fieldValue(buildFingerprint)}"

  private fun fieldValue(value: String): String =
    value.trim().replace(Regex("\\s+"), "_").ifBlank { "unknown" }
}
