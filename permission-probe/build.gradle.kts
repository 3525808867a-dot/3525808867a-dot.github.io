plugins {
    id("com.android.application")
}

android {
    namespace = "com.killsuoji.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.killsuoji.probe"
        minSdk = 20
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
        multiDexKeepFile = file("multidex-keep.txt")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
