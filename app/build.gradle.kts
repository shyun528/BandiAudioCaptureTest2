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
        versionCode = 4
        versionName = "4.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
}
