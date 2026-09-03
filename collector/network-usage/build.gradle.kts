plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cool.jacoblin.particeps.collector.networkusage"
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
    implementation(project(":core:collector-api"))
    implementation(project(":core:study-definition"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit4)
}
