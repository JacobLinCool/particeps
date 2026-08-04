plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cool.linc.androiddatacollector.collector.ambientlight"
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
    implementation(project(":collector:sensor-common"))
    implementation(project(":core:collector-api"))
    implementation(project(":core:study-definition"))
    testImplementation(libs.junit4)
}
