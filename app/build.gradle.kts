plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android { namespace = "com.codexrefresh.app"; compileSdk = 36
    defaultConfig { applicationId = "com.codexrefresh.app"; minSdk = 26; targetSdk = 36; versionCode = 7; versionName = "0.3.4" }
    buildFeatures { compose = true }
}

kotlin { jvmToolchain(21) }

dependencies {
    // Last stable train that remains compatible with this project's SDK 36 / AGP 8 setup.
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
