plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cool.jacoblin.particeps.core.storage"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

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
    api(project(":core:model"))
    api(project(":core:protocol"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotest.property)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

tasks.withType<Test>().configureEach {
    systemProperty("particeps.storage.property.iterations", providers.gradleProperty("storagePropertyIterations").orElse("48").get())
    systemProperty("particeps.storage.property.seed", providers.gradleProperty("storagePropertySeed").orElse("5782993918480303440").get())
}
