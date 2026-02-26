plugins {
    //noinspection GradleDependency
    id("com.android.application") version "8.9.3" apply false
    //noinspection GradleDependency
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    //noinspection GradleDependency
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    //noinspection GradleDependency — Hilt 2.59.2 requires AGP 9.0+, incompatible with current AGP 8.9.3
    id("com.google.dagger.hilt.android") version "2.56.2" apply false
    //noinspection GradleDependency
    id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
}
