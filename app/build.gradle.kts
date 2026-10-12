plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.wirl"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.wirl"
        minSdk = 26
        targetSdk = 35
        // CI passes -PversionCode / -PversionName (from the run number); local builds use 1 / "1.0".
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "1.0"
    }

    // CI points WIRL_KEYSTORE at the fixed key (from the DEBUG_KEYSTORE_BASE64 secret) so every
    // release installs over the last. Local builds fall back to the machine's debug key.
    val wirlKeystore = System.getenv("WIRL_KEYSTORE")?.let { file(it) }
    signingConfigs {
        if (wirlKeystore != null) {
            create("wirl") {
                storeFile = wirlKeystore
                storeType = "pkcs12"
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("wirl") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
