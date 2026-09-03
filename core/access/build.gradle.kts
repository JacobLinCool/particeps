plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cool.jacoblin.particeps.core.access"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(project(":collector:usage-common"))
    api(project(":core:collector-api"))
    implementation(libs.coroutines.play.services)
    implementation(libs.play.services.location)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit4)
}
