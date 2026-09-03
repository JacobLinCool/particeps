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

rootProject.name = "particeps"

include(
    ":actuator:traffic-shaping",
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
    ":collector:usage-common",
    ":collector:usage-events",
    ":core:access",
    ":core:automation",
    ":core:collector-api",
    ":core:crypto",
    ":core:experiment-runtime",
    ":core:export",
    ":core:model",
    ":core:protocol",
    ":core:resource-api",
    ":core:study-application",
    ":core:study-definition",
    ":core:storage",
    ":researcher-tools",
    ":test-fixtures:competing-vpn",
    ":test-fixtures:shared-uid-peer",
    ":test-fixtures:shared-uid-target",
    ":test-fixtures:traffic-control",
    ":test-fixtures:traffic-target-a",
    ":test-fixtures:traffic-target-b",
)
