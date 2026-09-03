plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cool.jacoblin.particeps.fixtures.sharedtarget"
    compileSdk = 37
    defaultConfig {
        applicationId = "cool.jacoblin.particeps.fixture.sharedtarget"
        minSdk = 28
        // Shared-user installation is a compatibility fixture, never a shipping target.
        targetSdk = 28
        versionCode = 1
        versionName = "1"
    }
    sourceSets["main"].java.directories.add(
        rootProject.file("test-fixtures/traffic-common/src/main/java").absolutePath,
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}
