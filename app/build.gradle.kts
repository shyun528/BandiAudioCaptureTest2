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
        versionCode = 6
        versionName = "6.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "bin"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
}
