plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Hilt Gradle Plugin
    id("com.google.dagger.hilt.android") version "2.48" apply false
    // Google Services (optional for Firebase/Crashlytics)
    id("com.google.gms.google-services") version "4.4.2" apply false
}

android {
    namespace = "com.example.novamusicplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.novamusicplayer"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Enable Jetifier for AndroidX compatibility
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.6.10"
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(com.google.android.material:material:1.12.0)
    implementation(androidx.activity:activity-compose:1.9.0)
    implementation(androidx.lifecycle:lifecycle-runtime-ktx:2.8.2)
    implementation(androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2)
    implementation(androidx.hilt:hilt-navigation-compose:1.2.0)
}
