plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.instantvoicetranslate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.instantvoicetranslate"
        minSdk = 29 // Android 10 — required for AudioPlaybackCapture
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += "lib/*/libonnxruntime.so"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.4")

    // Compose + Material 3
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.7")

    // Hilt DI — pinned at 2.56.2, Hilt 2.59.2 requires AGP 9.0+
    //noinspection GradleDependency
    implementation("com.google.dagger:hilt-android:2.56.2")
    //noinspection GradleDependency
    ksp("com.google.dagger:hilt-android-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    implementation("androidx.hilt:hilt-lifecycle-viewmodel-compose:1.3.0")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Sherpa-ONNX for streaming ASR (local AAR)
    implementation(files("libs/sherpa-onnx.aar"))

    // OkHttp for translation API + model downloads
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    // ONNX Runtime for NLLB offline translation inference
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // SentencePiece tokenizer for NLLB (DJL wrapper with Android support)
    implementation("ai.djl.sentencepiece:sentencepiece:0.36.0")

    // Apache Commons Compress for tar.bz2 extraction (punctuation model)
    implementation("org.apache.commons:commons-compress:1.28.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
