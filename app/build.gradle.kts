import java.util.Properties

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
    namespace = "cool.linc.androiddatacollector"
    compileSdk = 37

    defaultConfig {
        applicationId = "cool.linc.androiddatacollector"
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

dependencies {
    implementation(project(":collector:accelerometer"))
    implementation(project(":collector:app-lifecycle"))
    implementation(project(":collector:keyboard-ime"))
    implementation(project(":collector:location"))
    implementation(project(":collector:network-state"))
    implementation(project(":collector:network-usage"))
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

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
}
