plugins {
    alias(
        libs.plugins.android.application
    )

    alias(
        libs.plugins.kotlin.compose
    )

    id(
        "com.google.android.libraries.mapsplatform.secrets-gradle-plugin"
    )
}

/*
 * Apply KSP from the classpath declared in the
 * project-level build.gradle.kts.
 */
apply(
    plugin = "com.google.devtools.ksp"
)

android {
    namespace =
        "com.example.dashtool"

    compileSdk {
        version =
            release(36) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        applicationId =
            "com.example.dashtool"

        minSdk = 34
        targetSdk = 36

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_11

        targetCompatibility =
            JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    /*
     * Google location and restaurant routing.
     */
    implementation(
        "com.google.android.gms:play-services-location:21.4.0"
    )

    implementation(
        "com.google.android.libraries.places:places:5.3.0"
    )

    /*
     * On-device OCR.
     */
    implementation(
        "com.google.mlkit:text-recognition:16.0.1"
    )

    /*
     * Room local database.
     */
    /*
 * Room local database.
 *
 * Room KTX APIs are included in room-runtime
 * in current Room releases.
 */
    implementation(
        "androidx.room:room-runtime:2.8.4"
    )

    add(
        "ksp",
        "androidx.room:room-compiler:2.8.4"
    )

    /*
     * Compose and AndroidX.
     */
    implementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    implementation(
        libs.androidx.activity.compose
    )

    implementation(
        libs.androidx.compose.material3
    )

    implementation(
        libs.androidx.compose.ui
    )

    implementation(
        libs.androidx.compose.ui.graphics
    )

    implementation(
        libs.androidx.compose.ui.tooling.preview
    )

    implementation(
        libs.androidx.core.ktx
    )

    implementation(
        libs.androidx.lifecycle.runtime.ktx
    )

    /*
     * Tests.
     */
    testImplementation(
        libs.junit
    )

    androidTestImplementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )

    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}