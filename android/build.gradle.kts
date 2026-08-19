// Top-level build file where configuration common
// to all project modules can be declared.

buildscript {
    dependencies {
        classpath(
            "com.google.android.libraries.mapsplatform.secrets-gradle-plugin:" +
                    "secrets-gradle-plugin:2.0.1"
        )

        /*
         * KSP is loaded directly through the buildscript
         * because this project uses AGP 9 built-in Kotlin.
         */
        classpath(
            "com.google.devtools.ksp:" +
                    "symbol-processing-gradle-plugin:2.3.6"
        )
    }
}

plugins {
    alias(
        libs.plugins.android.application
    ) apply false

    alias(
        libs.plugins.kotlin.compose
    ) apply false
}