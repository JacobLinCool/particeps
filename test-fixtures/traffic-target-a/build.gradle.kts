plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "cool.jacoblin.particeps.fixtures.targeta"
    compileSdk = 37

    defaultConfig {
        applicationId = "cool.jacoblin.particeps.fixture.targeta"
        minSdk = 34
        targetSdk = 37
    }
    flavorDimensions += "revision"
    productFlavors {
        create("base") {
            dimension = "revision"
            versionCode = 1
            versionName = "1-base"
        }
        create("replacement") {
            dimension = "revision"
            versionCode = 2
            versionName = "2-replacement"
        }
    }
    sourceSets["main"].java.directories.add(
        rootProject.file("test-fixtures/traffic-common/src/main/java").absolutePath,
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}
