plugins {
    id("com.android.application") version "9.1.1" apply false
    // Kotlin is built-in to AGP 9.0+ — org.jetbrains.kotlin.android is no longer needed.
    // Compose compiler plugin is still applied separately.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
}
