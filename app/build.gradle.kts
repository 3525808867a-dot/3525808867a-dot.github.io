plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.killsuoji.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.killsuoji.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "2.4.1 beta"
    }
    val stableKeystorePath = System.getenv("KILLSUOJI_KEYSTORE_FILE")
    if (!stableKeystorePath.isNullOrBlank()) {
        signingConfigs.create("stable") {
            storeFile = file(stableKeystorePath)
            storePassword = System.getenv("KILLSUOJI_STORE_PASSWORD")
            keyAlias = System.getenv("KILLSUOJI_KEY_ALIAS")
            keyPassword = System.getenv("KILLSUOJI_KEY_PASSWORD")
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("stable")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("top.yukonga.miuix.kmp:miuix-android:0.3.8")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
