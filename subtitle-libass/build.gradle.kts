plugins {
    id("com.android.library")
}

// Match the app's explicit CI ABI target; an empty filter retains AGP's default ABIs.
val requestedAbis = providers.gradleProperty("targetAbi").orNull
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

android {
    namespace = "androidx.media3.subtitle.libass"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += requestedAbis }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
                arguments += listOf("-DMEDIA3_LIBASS_USE_SYSTEM_LIBASS=OFF")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures { buildConfig = false }
    buildTypes {
        create("preview") {
            initWith(getByName("release"))
        }
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    api(libs.androidx.media3.common)
    api(libs.androidx.media3.exoplayer)
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
