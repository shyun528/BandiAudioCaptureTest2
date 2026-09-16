plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.bandiaudiocapture"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.bandiaudiocapture"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
