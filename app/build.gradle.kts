import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
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

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile", ""))
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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

    lint {
        // onnxruntime-android:1.17.1 native lib is not 16KB-aligned, but the APK
        // uses libonnxruntime.so from sherpa-onnx.aar (via pickFirsts), not Maven.
        disable += "Unaligned16KbNativeLibs"
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.4")

    // Compose + Material 3
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
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
    //noinspection NewerVersionAvailable,GradleDependency
    implementation("com.google.dagger:hilt-android:2.56.2")
    //noinspection NewerVersionAvailable,GradleDependency
    ksp("com.google.dagger:hilt-android-compiler:2.56.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    implementation("androidx.hilt:hilt-lifecycle-viewmodel-compose:1.3.0")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    // DocumentFile for SAF directory access (diagnostics output)
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Sherpa-ONNX for streaming ASR (local AAR)
    implementation(files("libs/sherpa-onnx.aar"))

    // OkHttp for translation API + model downloads
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    // ONNX Runtime for NLLB offline translation inference
    // MUST match version bundled in sherpa-onnx.aar (1.17.1) to avoid symbol versioning conflict.
    // 16KB alignment warning is safe to ignore: the actual libonnxruntime.so in the APK
    // comes from sherpa-onnx.aar via pickFirsts, not from this Maven dependency.
    //noinspection Aligned16KB,NewerVersionAvailable,GradleDependency,Unaligned16KbNativeLibs
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")


    // Apache Commons Compress for tar.bz2 extraction (punctuation model)
    implementation("org.apache.commons:commons-compress:1.28.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
