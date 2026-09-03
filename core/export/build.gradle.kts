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
    implementation(project(":core:automation"))
    implementation(project(":core:collector-api"))
    api(project(":core:model"))
    api(project(":core:protocol"))
    api(project(":core:study-definition"))
    implementation(project(":core:crypto"))
    implementation(libs.gson)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit4)
}

val kotlinExportInteropDirectory = providers.environmentVariable("PARTICEPS_KOTLIN_EXPORT_INTEROP_DIR")
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (kotlinExportInteropDirectory.isPresent) {
        inputs.property("particepsKotlinExportInteropDirectory", kotlinExportInteropDirectory)
        outputs.dir(kotlinExportInteropDirectory)
        outputs.cacheIf("Interop output contains an ephemeral test-only private key") { false }
    }
}
