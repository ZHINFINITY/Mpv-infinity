/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.androidx.baselineprofile)
}

android {
  namespace = "app.infinity.mpvz.baselineprofile"
  compileSdk = 37
  targetProjectPath = ":app"

  defaultConfig {
    minSdk = 26
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Match the app's distribution dimension so the plugin can resolve the
  // standardRelease target used by the CI profile run.
  flavorDimensions += "distribution"
  productFlavors {
    create("standard") {
      dimension = "distribution"
    }
    create("noVulkan") {
      dimension = "distribution"
    }
  }

  testOptions {
    execution = "ANDROIDX_TEST_ORCHESTRATOR"
  }
}

baselineProfile {
  useConnectedDevices = true
}

dependencies {
  implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
  implementation("androidx.test:runner:1.7.0")
  implementation("androidx.test.uiautomator:uiautomator:2.4.0")
  androidTestUtil("androidx.test:orchestrator:1.6.1")
}
