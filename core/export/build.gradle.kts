plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:study-definition"))
    implementation(project(":core:crypto"))
    implementation(libs.gson)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit4)
}
