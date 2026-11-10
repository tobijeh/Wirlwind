import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * sherpa-onnx runs the Cantonese model (SenseVoice). Its official Android library (.aar, Kotlin API plus native libraries)
 * is pinned to one release and checked against its SHA-256 before it's used. It's fetched once into .deps/ (not in git,
 * since it's 49 MB). Change the version on purpose, update the hash, and test on a phone.
 */
val sherpaOnnxVersion = "2.0.1"
val sherpaOnnxSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaOnnxAar: File = rootProject.file(".deps/sherpa-onnx-$sherpaOnnxVersion.aar").also { aar ->
    fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    if (!aar.isFile || sha256(aar) != sherpaOnnxSha256) {
        aar.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
        logger.lifecycle("Downloading $url")
        val part = File(aar.path + ".part")
        URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val actual = sha256(part)
        if (actual != sherpaOnnxSha256) {
            part.delete()
            throw GradleException("sherpa-onnx $sherpaOnnxVersion has SHA-256 $actual, expected $sherpaOnnxSha256")
        }
        part.renameTo(aar)
    }
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
        // Whisper and SenseVoice run on arm64 phones only. Other ABIs would be missing the native libraries.
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    // whisper.cpp is built from source by CMake (see app/src/main/cpp/CMakeLists.txt).
    ndkVersion = "26.1.10909125"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        jniLibs {
            // sherpa-onnx's C and C++ APIs aren't used: the app talks to it through the JNI library.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Dictionary sync: background runs, and the pairing QR scanner.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Cantonese: SenseVoice through sherpa-onnx (pinned and checked above), and tar.bz2 unpacking for its model.
    implementation(files(sherpaOnnxAar))
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.work:work-testing:2.9.1")
}
