plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin.compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    allWarningsAsErrors = true
}

dependencies {
    api(project(":core:collector-api"))
    api(project(":core:experiment-runtime"))
    api(project(":core:export"))
    api(project(":core:protocol"))
    api(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit4)
}
