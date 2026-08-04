pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-data-collector"

include(
    ":app",
    ":collector:accelerometer",
    ":collector:app-lifecycle",
    ":collector:ambient-light",
    ":collector:battery-state",
    ":collector:gyroscope",
    ":collector:keyboard-ime",
    ":collector:location",
    ":collector:network-state",
    ":collector:network-usage",
    ":collector:proximity",
    ":collector:sensor-common",
    ":collector:temporal-context",
    ":collector:usage-events",
    ":core:access",
    ":core:collector-api",
    ":core:crypto",
    ":core:experiment-runtime",
    ":core:export",
    ":core:model",
    ":core:protocol",
    ":core:study-application",
    ":core:study-definition",
    ":core:storage",
    ":researcher-tools",
)
