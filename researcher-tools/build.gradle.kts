plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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

application {
    mainClass = "cool.jacoblin.particeps.researcher.MainKt"
}

tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
}

// Real runtime bundle fixtures default to particeps-analysis/tests/fixtures/runtime-bundles;
// -Pparticeps.runtimeBundleFixtures=DIR[:DIR...] points RuntimeBundleFixtureTest elsewhere.
val runtimeBundleFixtures = providers.gradleProperty("particeps.runtimeBundleFixtures")
val runtimeBundleFixtureRoots = runtimeBundleFixtures.orNull?.split(File.pathSeparator)
    ?: listOf(rootProject.file("particeps-analysis/tests/fixtures/runtime-bundles").path)
tasks.withType<Test>().configureEach {
    systemProperty("particeps.repository.root", rootProject.projectDir.absolutePath)
    runtimeBundleFixtures.orNull?.let { systemProperty("particeps.runtime.bundle.fixtures", it) }
    inputs.files(runtimeBundleFixtureRoots.map { rootProject.fileTree(it) })
        .withPropertyName("runtimeBundleFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":core:export"))
    implementation(project(":core:protocol"))
    implementation(project(":core:study-definition"))
    implementation(libs.gson)
    testImplementation(libs.junit4)
}
