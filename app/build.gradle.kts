import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val releaseSigningPropertiesFile = rootProject.file(".signing/release-signing.properties")
val releaseSigningProperties = releaseSigningPropertiesFile.takeIf { it.isFile }?.inputStream()?.use { input ->
    Properties().apply { load(input) }
}

fun Properties.requireSigningValue(name: String): String =
    getProperty(name)?.takeIf { it.isNotEmpty() }
        ?: error("Missing $name in ${releaseSigningPropertiesFile.path}")

android {
    namespace = "cool.jacoblin.particeps"
    compileSdk = 37

    defaultConfig {
        applicationId = "cool.jacoblin.particeps"
        minSdk = 34
        targetSdk = 37
        versionCode = providers.gradleProperty("releaseVersionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("releaseVersionName").getOrElse("1.0.0-dev")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseSigningProperties?.let { properties ->
            create("release") {
                storeFile = rootProject.file(properties.requireSigningValue("storeFile"))
                storePassword = properties.requireSigningValue("storePassword")
                keyAlias = properties.requireSigningValue("keyAlias")
                keyPassword = properties.requireSigningValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "DebugProbesKt.bin",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("particeps.appProjectDir", projectDir.absolutePath)
}

dependencies {
    implementation(project(":actuator:traffic-shaping"))
    implementation(project(":collector:accelerometer"))
    implementation(project(":collector:app-lifecycle"))
    implementation(project(":collector:ambient-light"))
    implementation(project(":collector:battery-state"))
    implementation(project(":collector:gyroscope"))
    implementation(project(":collector:keyboard-ime"))
    implementation(project(":collector:location"))
    implementation(project(":collector:network-state"))
    implementation(project(":collector:network-usage"))
    implementation(project(":collector:proximity"))
    implementation(project(":collector:temporal-context"))
    implementation(project(":collector:usage-events"))
    implementation(project(":core:access"))
    implementation(project(":core:collector-api"))
    implementation(project(":core:experiment-runtime"))
    implementation(project(":core:export"))
    implementation(project(":core:model"))
    implementation(project(":core:protocol"))
    implementation(project(":core:study-application"))
    implementation(project(":core:study-definition"))
    implementation(project(":core:storage"))

    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)

    // Source-built by :actuator:traffic-shaping; the repository never carries a prebuilt AAR or
    // shared object. The application dependency is what packages the four verified native ABIs.
    implementation(
        files(
            rootProject.layout.buildDirectory.file(
                "generated/traffic-shaping/particeps-traffic-shaping.aar",
            ),
        ).builtBy(":actuator:traffic-shaping:buildNativeTrafficShapingAar"),
    )

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.concurrent.futures)
    // Only to read the shared Protocol v1 corpus in a test; no production code parses JSON this way.
    testImplementation(libs.gson)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
}
