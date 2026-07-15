plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fy.printterminal"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "fycrg-app-release-key.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "fycrg123456"
            keyAlias = System.getenv("KEY_ALIAS") ?: "fycrg-app"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "fycrg123456"
        }
    }

    defaultConfig {
        applicationId = "com.fy.printterminal"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.8.1"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
