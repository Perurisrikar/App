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
        versionCode = 1
        versionName = "1.0"
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
