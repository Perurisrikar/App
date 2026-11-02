plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.waautoreply"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.waautoreply"
        minSdk = 26
        targetSdk = 34
        // GitHub passes the build number so the app can tell when a newer build exists
        versionCode = (project.findProperty("vc") as String?)?.toIntOrNull() ?: 1
        versionName = "1.0"
    }

    // Same signing key for every build, so updates install over the old app
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("release.jks")
            storePassword = "waai-pass-123"
            keyAlias = "waai"
            keyPassword = "waai-pass-123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("shared") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "task" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
