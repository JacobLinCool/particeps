import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
}

val nativeAar = rootProject.layout.buildDirectory.file(
    "generated/traffic-shaping/particeps-traffic-shaping.aar",
)
val nativeSourceDirectory = rootProject.layout.projectDirectory.dir("native/traffic-shaping")
val androidSdk = providers.environmentVariable("ANDROID_SDK_ROOT")
    .orElse(providers.environmentVariable("ANDROID_HOME"))
    .get()

val buildNativeTrafficShapingAar = tasks.register<Exec>("buildNativeTrafficShapingAar") {
    group = "build"
    description = "Builds the pinned gomobile traffic-shaping AAR from source."
    inputs.files(
        rootProject.fileTree(nativeSourceDirectory) {
            exclude("**/.DS_Store")
        },
    )
    outputs.file(nativeAar)
    environment("ANDROID_HOME", androidSdk)
    environment("ANDROID_NDK_HOME", "$androidSdk/ndk/30.0.14904198")
    commandLine(
        nativeSourceDirectory.file("build-aar.sh").asFile.absolutePath,
        nativeAar.get().asFile.absolutePath,
    )
}

val nativeBinding = files(nativeAar).builtBy(buildNativeTrafficShapingAar)

android {
    namespace = "cool.jacoblin.particeps.actuator.trafficshaping"
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

tasks.withType<Test>().configureEach {
    systemProperty("particeps.repositoryRoot", rootProject.projectDir.absolutePath)
}

dependencies {
    api(project(":core:resource-api"))
    implementation(project(":core:model"))
    implementation(libs.coroutines.android)

    // The Android app packages this same generated artifact. compileOnly prevents an Android
    // library from embedding a local AAR while retaining a strongly typed gomobile boundary.
    compileOnly(nativeBinding)

    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit4)
}
