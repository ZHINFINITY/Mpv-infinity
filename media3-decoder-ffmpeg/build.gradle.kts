import org.gradle.api.tasks.Exec

plugins {
  id("com.android.library")
}

val generatedRoot = layout.buildDirectory.dir("generated/media3-dca")
val ffmpegHeadersDir = generatedRoot.map { it.dir("ffmpeg-source") }
val ffmpegRuntimeLibsDir = generatedRoot.map { it.dir("runtime") }
val prepareMedia3DcaInputs =
  tasks.register<Exec>("prepareMedia3DcaInputs") {
    val prepareScript = rootProject.file("scripts/prepare-media3-dca-native.sh")
    val runtimeAar = rootProject.file("app/libs/mpvlib.aar")
    inputs.file(prepareScript)
    inputs.file(runtimeAar)
    inputs.property("ffmpegSourceCommit", "946fcce07b6dcd0331c8cc609192aeff5e1924f8")
    outputs.dir(generatedRoot)
    doFirst {
      val ndkDir = android.sdkComponents.ndkDirectory.get().asFile
      commandLine(
        "bash",
        prepareScript.absolutePath,
        generatedRoot.get().asFile.absolutePath,
        ndkDir.absolutePath,
        runtimeAar.absolutePath,
      )
    }
  }

tasks.configureEach {
  if (
    name.startsWith("configureCMake") ||
      name.startsWith("buildCMake") ||
      name.startsWith("externalNativeBuild")
  ) {
    dependsOn(prepareMedia3DcaInputs)
  }
}

android {
  namespace = "androidx.media3.decoder.ffmpeg"
  compileSdk = 37
  ndkVersion = "27.3.13750724"

  defaultConfig {
    minSdk = 26
    consumerProguardFiles("consumer-rules.pro")
    ndk {
      abiFilters += listOf("arm64-v8a")
    }
    externalNativeBuild {
      cmake {
        cppFlags += listOf("-std=c++11")
        arguments +=
          listOf(
            "-DMEDIA3_FFMPEG_SOURCE_DIR=${ffmpegHeadersDir.get().asFile.absolutePath}",
            "-DMEDIA3_FFMPEG_RUNTIME_LIBS_DIR=${ffmpegRuntimeLibsDir.get().asFile.absolutePath}",
          )
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/jni/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  buildFeatures {
    buildConfig = false
  }

  packaging {
    jniLibs {
      useLegacyPackaging = false
    }
  }
}

dependencies {
  api(libs.androidx.media3.common)
  api(libs.androidx.media3.exoplayer)
  api(libs.androidx.media3.decoder)
  implementation("androidx.annotation:annotation:1.9.1")
  testImplementation("junit:junit:4.13.2")
}
