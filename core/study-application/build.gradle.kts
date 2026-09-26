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
    testImplementation(project(":core:crypto"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit4)
}

// RealRuntimeBundleInteropTest runs the published five-day pilot configuration. With
// PARTICEPS_REAL_RUNTIME_INTEROP_DIR set it also writes its bundles there for particeps-analysis.
val pilotConfiguration = rootProject.file("web/static/studies/internal-five-day-optional-usage-20260914/study.json")
val realRuntimeInteropDirectory = providers.environmentVariable("PARTICEPS_REAL_RUNTIME_INTEROP_DIR")
tasks.withType<Test>().configureEach {
    systemProperty("particeps.pilot.configuration", pilotConfiguration.absolutePath)
    inputs.file(pilotConfiguration)
        .withPropertyName("pilotConfiguration")
        .withPathSensitivity(PathSensitivity.NONE)
    if (realRuntimeInteropDirectory.isPresent) {
        inputs.property("particepsRealRuntimeInteropDirectory", realRuntimeInteropDirectory)
        outputs.dir(realRuntimeInteropDirectory)
        outputs.cacheIf("Interop output contains an ephemeral test-only private key") { false }
    }
}
