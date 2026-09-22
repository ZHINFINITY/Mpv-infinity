plugins {
    id("com.android.library")
}

android {
    namespace = "androidx.media3.subtitle.libass"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
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
    api("androidx.media3:media3-common:1.6.1")
    api("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
