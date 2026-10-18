plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.vara.mediatracker.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // Baseline profiles are collected through Profileable, which needs API 28.
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Everything this module measures comes from the app under test.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.espresso.core)
}
